package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.squareup.tape2.QueueFile;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.primitive.IS;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.FN;
import ca.uhn.hl7v2.model.v251.datatype.HD;
import ca.uhn.hl7v2.model.v251.datatype.ST;
import ca.uhn.hl7v2.model.v251.datatype.TS;
import ca.uhn.hl7v2.model.v251.datatype.XAD;
import ca.uhn.hl7v2.model.v251.datatype.XCN;
import ca.uhn.hl7v2.model.v251.datatype.XON;
import ca.uhn.hl7v2.model.v251.datatype.XPN;
import ca.uhn.hl7v2.model.v251.datatype.XTN;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import ca.uhn.hl7v2.model.v251.segment.MSH;
import ca.uhn.hl7v2.model.v251.segment.OBR;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import ca.uhn.hl7v2.model.v251.segment.ORC;
import ca.uhn.hl7v2.model.v251.segment.PID;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import ca.uhn.hl7v2.util.Terser;

/*
 * HL7v2 Message Receiver Application for ELR
 * 
 * Author : Myung Choi (myung.choi@gtri.gatech.edu)
 * Version: 0.1-beta
 * 
 * Implementation Guide: V251_IG_LB_LABRPTPH_R2_DSTU_R1.1_2014MAY.PDF (Available from HL7.org)
 */

public class HL7v2ReceiverApplication implements ReceivingApplication<Message> {
	private String phcr_controller_api_url;
	private boolean useTls;
	private QueueFile queueFile = null;
	private TimerTask timerTask = null;
	private Timer timer= null;

	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v2ReceiverApplication.class.getName());
	
	// Error Status
	static int PID_ERROR = -1;
	static enum ErrorCode {
		NOERROR, MSH, PID, ORDER_OBSERVATION, LAB_RESULTS, INTERNAL;
	}
	
	public static JSONObject parseJSONFile(String filename) throws JSONException, IOException {
        String content = new String(Files.readAllBytes(Paths.get(filename)));
        return new JSONObject(content);
    }
	
	public void config(String phcr_controller_api_url, boolean useTls, String qFileName, String ecr_template_filename) 
		throws Exception {
		this.phcr_controller_api_url = phcr_controller_api_url;
		this.useTls = useTls;
		
		// Set up QueueFile
		if (queueFile == null) {
			File file = new File(qFileName);
			queueFile = new QueueFile.Builder(file).build();
		}
		
		// After QueueFile is set up, we start background service.
		if (timerTask == null)
			timerTask = new QueueTaskTimer(this);
		
		if (timer == null)
			timer = new Timer(true);
		else
			timer.cancel();
		
		timer.scheduleAtFixedRate(timerTask, 20*1000, 10*1000);
	}
	
	public boolean canProcess(Message theMessage) {
		// We accepts when the follow conditions met.
		// - MSH-21 Message Profile Identifier: We need to talk to Lab (eg Labcorp) to make sure
		//          that we use correct profile. ELR document has the following in the example
		//          LRI_Common_Component^^2.16.840.1.113883.9 .16^ISO~LRI_GU_Component^^2.16.840.1.113883.9.12^ISO~LAB_RU_Componen t^^2.16.840.1.113883.9.14^ISO
		// - MSH-12.1: 2.5.1  We support HL7 v2.5.1
		// - MSH-9(1.2.3): ORU^R01^ORU_R01 messages (in 1.10.1)
		
		// Check MSH-21 for the message profile
		// TODO: Implement this after discussing with LabCorp
		
		// Check the version = v2.5.1
		if (theMessage.getVersion().equalsIgnoreCase("2.5.1") == false) {
			LOGGER.info("Message Received, but not not v2.5.1. Received message version is "+theMessage.getVersion());
			return false;
		}
		
		// Check the message type
		Terser t = new Terser(theMessage);
		try {
			if (t.get("/MSH-9-1").equalsIgnoreCase("ORU") == false 
					|| t.get("/MSH-9-2").equalsIgnoreCase("R01") == false
					|| t.get("/MSH-9-3").equalsIgnoreCase("ORU_R01") == false) {
				LOGGER.info("Message with correct version received, but not ORU_R01 message type. Receved message type: "+t.get("/MSH-9-1")+" "+t.get("/MSH-9-2")+" "+t.get("/MSH-9-3"));
				return false;
			}
		} catch (HL7Exception e) {
			e.printStackTrace();
			return false;
		}
		
		return true;
	}

	public Message processMessage(Message theMessage, Map<String, Object> theMetadata)
			throws ReceivingApplicationException, HL7Exception {

//		String encodedMessage = new DefaultHapiContext().getPipeParser().encode(theMessage);
//		System.out.println("Received message:\n"+ encodedMessage + "\n\n");
		
		ErrorCode error = map2ecr((ORU_R01) theMessage);
		if (error != ErrorCode.NOERROR) {
			// Create an exception.
			throw new HL7Exception(error.toString());
		}
		
		try {
//			send_ecr(ecr_json);
			return theMessage.generateACK();
		} catch (IOException e) {
			throw new HL7Exception(e);
		} catch (Exception e) {
			throw new ReceivingApplicationException(e);
		}
	}
	
	String make_single_address (XAD addressXAD) {
		String ret = "";
		
		String address = addressXAD.getStreetAddress().getStreetOrMailingAddress().getValueOrEmpty();
		String city = addressXAD.getCity().getValueOrEmpty();
		String state = addressXAD.getStateOrProvince().getValueOrEmpty();
		String zip = addressXAD.getZipOrPostalCode().getValueOrEmpty();
		
		if (!address.isEmpty()) ret = address;
		if (!city.isEmpty()) {
			if (ret.isEmpty()) ret = city;
			else ret += ", "+city;
		}
		if (!state.isEmpty()) {
			if (ret.isEmpty()) ret = state;
			else ret += " "+state;
		}
		if (!zip.isEmpty()) {
			if (ret.isEmpty()) ret = zip;
			else ret += " "+zip;
		}

		return ret;
	}
	
	private int put_CE_to_json (CE element, JSONObject json_obj) {
		String System = element.getNameOfCodingSystem().getValueOrEmpty();
		String Code = element.getIdentifier().getValueOrEmpty();
		String Display = element.getText().getValueOrEmpty();
		
		if (System.isEmpty() && Code.isEmpty() && Display.isEmpty()) {
			json_obj.put("System", element.getNameOfAlternateCodingSystem().getValueOrEmpty());
			json_obj.put("Code", element.getAlternateIdentifier().getValueOrEmpty());
			json_obj.put("Display", element.getAlternateText().getValueOrEmpty());
			return -1;
		} else {
			json_obj.put("System", System);
			json_obj.put("Code", Code);
			json_obj.put("Display", Display);	
			return 0;
		}
	}

	/*
	 * We selects the patient ID that has assigned auth
	 * name as "EMR". If this does not exist, then we choose the last patient ID from the list.
	 * 
	 * Patient Name is selected from the same section. If multiple names exist, we choose
	 * the first one.
	 * 
	 * Patient ID: PID-3-1. This is required field. If this is not available, it returns -1
	 * Patient Name: PID-5. This is required field. If this is not available, it returns -1
	 * Date of Birth: PID-7. 
	 * Administrative Sex: PID-8
	 * Race: PID-10
	 * Address: PID-11
	 * Preferred Language: PID-15
	 * Ethnicity: PID-22
	 * 
	 */
	private int map_patient (ORU_R01_PATIENT patient, JSONObject ecr_json) {
		String patientID = "";
		String patientName_last = "";
		String patientName_given = "";
		String patientName_middle = "";
				
		String System, Code, Display;
		
		JSONObject patient_json;
		if (ecr_json.isNull("Patient")) {
			patient_json = new JSONObject();
			ecr_json.put("Patient", patient_json);
		} else
			patient_json = ecr_json.getJSONObject("Patient");
		
		
//		JSONObject patient_json = ecr_json.getJSONObject("Patient");

		// Patient information itself
		PID pid_seg = patient.getPID();
		int totPID3 = pid_seg.getPid3_PatientIdentifierListReps();
		for (int j=0; j<totPID3; j++) {
			CX pIdentifier = pid_seg.getPid3_PatientIdentifierList(j);
			
			// From PID-3-1 (ID Number) is REQUIRED. So, get this one.
			patientID = pIdentifier.getIDNumber().getValue();
			
			// Rest of Extended Composite ID are all optional. So, we get
			// them if available.
			HD pIdAssignAuth = pIdentifier.getAssigningAuthority();
			String AssignAuthName = pIdAssignAuth.getNamespaceID().getValueOrEmpty();

			// Patient ID Number and Assigning Authority Name Space (user defined)
			// will probably sufficient to check.
			if (!AssignAuthName.isEmpty()) {
				if (AssignAuthName.equalsIgnoreCase("EMR")) {
					break;
				}
			}
		}
		
		int totPatientNames = pid_seg.getPid5_PatientNameReps();
		for (int j=0; j<totPatientNames; j++) {
			XPN patientName_xpn = pid_seg.getPid5_PatientName(j);
			FN f_name = patientName_xpn.getFamilyName();
			ST f_name_st = f_name.getFn1_Surname();
			patientName_last = f_name_st.getValueOrEmpty();
			ST m_name = patientName_xpn.getGivenName();
			patientName_given = m_name.getValueOrEmpty();
			
			if (patientName_last.isEmpty() && patientName_given.isEmpty()) {
				continue;
			}
			
			ST f_name_initial = patientName_xpn.getSecondAndFurtherGivenNamesOrInitialsThereof();
			patientName_middle = f_name_initial.getValueOrEmpty();
		}
					
		// We need patient id or name for rest to be useful. 
		// If not, we move to the next patient result.
		if (patientID == "" 
				&& (patientName_given.isEmpty() && patientName_last.isEmpty()))
			return 0;
		
		// Set all the collected patient information to ECR
		JSONObject patient_name = new JSONObject();
		patient_json.put("Name", patient_name);
		
		patient_json.put("ID", patientID);
		patient_name.put("given", patientName_given);
		patient_name.put("family", patientName_last);
		
		// DOB parse.
		TS dateTimeDOB = pid_seg.getDateTimeOfBirth();
		try {
			if (dateTimeDOB.isEmpty() == false) {
				patient_json.put("Birth_Date", dateTimeDOB.getTime().getValue());
			}
		} catch (HL7Exception e) {
			e.printStackTrace();
		}
		
		// Administrative Sex
		IS gender = pid_seg.getAdministrativeSex();
		patient_json.put("Sex", gender.getValueOrEmpty());

		// Race
		JSONObject patient_race = new JSONObject();
		patient_json.put("Race", patient_race);
		
		String System2Use = "";
		String Code2Use = "";
		String Display2Use = "";
		int totRaces = pid_seg.getRaceReps();
		for (int j=0; j<totRaces; j++) {
			CE raceCodedElement = pid_seg.getRace(j);
			System = raceCodedElement.getNameOfCodingSystem().getValueOrEmpty();
			Code = raceCodedElement.getIdentifier().getValueOrEmpty();
			Display = raceCodedElement.getText().getValueOrEmpty();
			
			if (j==0) {
				// First time. Save the parameters
				System2Use = System;
				Code2Use = Code;
				Display2Use = Display;
			}
			
			if (System.isEmpty() && Code.isEmpty() && Display.isEmpty()) {
				// Before we move to the next race. Check if we have alternative system.
				System = raceCodedElement.getNameOfAlternateCodingSystem().getValueOrEmpty();
				Code = raceCodedElement.getAlternateIdentifier().getValueOrEmpty();
				Display = raceCodedElement.getAlternateText().getValueOrEmpty();
				if (System.isEmpty() && Code.isEmpty() && Display.isEmpty()) 
					continue;
			}
			
			if (!System.isEmpty() && !Code.isEmpty() && !Display.isEmpty()) {
				// Done. All three parameters are available.
				System2Use = System;
				Code2Use = Code;
				Display2Use = Display;
				break;
			}

			if (!System.isEmpty() && !Code.isEmpty()) {
				System2Use = System;
				Code2Use = Code;
				Display2Use = Display;					
			} else if ((System2Use.isEmpty() || Code2Use.isEmpty()) && !Display.isEmpty()) {
				System2Use = System;
				Code2Use = Code;
				Display2Use = Display;
			}
		}
		patient_race.put("System", System2Use);
		patient_race.put("Code", Code2Use);
		patient_race.put("Display", Display2Use);

		// Address
		int totAddresses = pid_seg.getPatientAddressReps();
		for (int j=0; j<totAddresses; j++) {
			String address = make_single_address (pid_seg.getPatientAddress(j));
			if (address == "") continue;
			patient_json.put("Street_Address", address);
			break;
		}
		
		// Preferred Language
		JSONObject patient_preferred_lang = new JSONObject();
		
		CE primaryLangCE = pid_seg.getPrimaryLanguage();
		if (put_CE_to_json(primaryLangCE, patient_preferred_lang) == 0)
			patient_json.put("Preferred_Language", patient_preferred_lang);
		
		// Ethnicity
		JSONObject patient_ethnicity = new JSONObject();
		int totEthnicity = pid_seg.getEthnicGroupReps();
		for (int j=0; j<totEthnicity; j++) {
			CE ethnicityCE = pid_seg.getEthnicGroup(j);
			if (put_CE_to_json(ethnicityCE, patient_ethnicity) == 0)
				patient_json.put("Ethnicity", patient_ethnicity);
		}
		
		return 1;
	}
		
	/*
	 *   ORDER OBSERVATION List
	 *   OBR-16. Use ORC-12 if OBR-16 is empty.
	 */
	private JSONObject map_order_observation (ORU_R01_ORDER_OBSERVATION orderObs) {
		// Create a lab order JSON and put it in the lab order array.
		JSONObject ecr_laborder_json = new JSONObject();
		
		// Get OBR and ORC information.
		OBR orderRequest = orderObs.getOBR();
		ORC common_order = orderObs.getORC();

		// Get lab order information. This is a coded element.
		CE labOrderCE = orderRequest.getUniversalServiceIdentifier();
		put_CE_to_json (labOrderCE, ecr_laborder_json);

		// Provider. We have provider information in OBR and ORC. We check if we OBR has
		// a provider information. If not, we try with ORC.
		JSONObject provider_json = new JSONObject();
		int totalProviders = orderRequest.getOrderingProviderReps();
		String type = "OBR";
		if (totalProviders == 0) {
			totalProviders = common_order.getOrderingProviderReps();
			type = "ORC";
		}
		
		// There may be multiple providers. But, we hold only one provider per order.
		boolean ok_to_put = false;
		for (int i=0; i<totalProviders; i++) {
			XCN orderingProviderXCN;
			if (type.equalsIgnoreCase("OBR"))
				orderingProviderXCN = orderRequest.getOrderingProvider(i);
			else
				orderingProviderXCN = common_order.getOrderingProvider(i);
			
			String orderingProviderID = orderingProviderXCN.getIDNumber().getValueOrEmpty();
			if (!orderingProviderID.isEmpty()) {
				provider_json.put("ID", orderingProviderID);
				ok_to_put = true;
			}
		
			String providerFamily = orderingProviderXCN.getFamilyName().getFn1_Surname().getValueOrEmpty();
			String providerGiven = orderingProviderXCN.getGivenName().getValueOrEmpty();
			String providerInitial = orderingProviderXCN.getSecondAndFurtherGivenNamesOrInitialsThereof().getValueOrEmpty();
			String providerSuffix = orderingProviderXCN.getSuffixEgJRorIII().getValueOrEmpty();
			String providerPrefix = orderingProviderXCN.getPrefixEgDR().getValueOrEmpty();
			
			String orderingProviderName = "";
			if (!providerPrefix.isEmpty()) orderingProviderName = providerPrefix+" ";
			if (!providerGiven.isEmpty()) orderingProviderName += providerGiven+" ";
			if (!providerInitial.isEmpty()) orderingProviderName += providerInitial+" ";
			if (!providerFamily.isEmpty()) orderingProviderName += providerFamily+" ";
			if (!providerSuffix.isEmpty()) orderingProviderName += providerSuffix+" ";
			
			orderingProviderName = orderingProviderName.trim();
			if (!orderingProviderName.isEmpty()) {
				provider_json.put("Name", orderingProviderName);
				ok_to_put = true;
			}
			
			if (!orderingProviderID.isEmpty() && !orderingProviderName.isEmpty()) {
				break;
			}
		}
		
		if (ok_to_put) ecr_laborder_json.put("Provider", provider_json);		
		
		// Observation Time
		String observationTime = orderRequest.getObservationDateTime().getTime().getValue();
		if (observationTime != null && !observationTime.isEmpty())
			ecr_laborder_json.put("DateTime", observationTime);
		
		// Reason for Study		
		JSONArray reasons_json = new JSONArray();
		int totalReasons = orderRequest.getReasonForStudyReps();
		ok_to_put = false;
		for (int i=0; i<totalReasons; i++) {
			CE reasonCE = orderRequest.getReasonForStudy(i);
			JSONObject reason_json = new JSONObject();
			if (put_CE_to_json (reasonCE, reason_json) == 0) {
				reasons_json.put(reason_json);
				ok_to_put = true;
			}
		}
		
		if (ok_to_put) ecr_laborder_json.put("Reasons", reasons_json);
		
		// Below is ORC ---
		// Facility
		// There may be multiple facilities. We get only one of them. 
		ok_to_put = false;
		JSONObject facility_json = new JSONObject();
		int totalFacilityNames = common_order.getOrderingFacilityNameReps();
		for (int i=0; i<totalFacilityNames; i++) {
			XON orderFacilityXON = common_order.getOrderingFacilityName(i);
			String orgName = orderFacilityXON.getOrganizationName().getValueOrEmpty();
			if (!orgName.isEmpty()) {
				ok_to_put = true;
				facility_json.put("Name", orgName);
				String orgID = orderFacilityXON.getIDNumber().getValue();
				if (orgID != null) {
					facility_json.put("ID", orgID);
				}
				
				// If we have orgID, then that's enough. break!
				if (orgID != null) break;
			}
		}
		
		// Facility Phone Number
		// There may be multiple phone numbers. We get only one.
		int totalFacilityPhones = common_order.getOrderingFacilityPhoneNumberReps();
		for (int i=0; i<totalFacilityPhones; i++) {
			XTN orderFacilityPhoneXTN = common_order.getOrderingFacilityPhoneNumber(i);
			String country = orderFacilityPhoneXTN.getCountryCode().getValue();
			String area = orderFacilityPhoneXTN.getAreaCityCode().getValue();
			String local = orderFacilityPhoneXTN.getLocalNumber().getValue();
			
			String phone = "";
			if (!country.isEmpty()) phone = country;
			if (!area.isEmpty()) phone += "-"+area;
			if (!local.isEmpty()) phone += "-"+local;
			
			if (!phone.isEmpty()) {
				ok_to_put = true;
				facility_json.put("Phone", phone);
			}
			
			if (!country.isEmpty() && !area.isEmpty() && !local.isEmpty()) {
				break;
			} else {
				String backward_phone = common_order.getOrderingFacilityPhoneNumber(i).getTelephoneNumber().getValueOrEmpty();
				if (!backward_phone.isEmpty()) {
					ok_to_put = true;
					facility_json.put("Phone", backward_phone);
					break;
				}
			}
		}
		
		// Facility Address
		// There may be multiple facility addresses. We get only one.
		int totalFacilityAddress = common_order.getOrderingProviderAddressReps();
		for (int i=0; i<totalFacilityAddress; i++) {
			XAD addressXAD = common_order.getOrderingProviderAddress(i);
			String address = make_single_address (addressXAD);
			if (address=="") continue;
			ok_to_put = true;
			facility_json.put("Address", address);
			break;
		}
		
		if (ok_to_put) ecr_laborder_json.put("Facility", facility_json);

		return ecr_laborder_json;
	}
	
	/*
	 * Lab Results (OBXes)
	 */
	private JSONObject map_lab_result(OBX obsResult) {
		JSONObject labresult_json = new JSONObject();
			
		CE obsCE = obsResult.getObservationIdentifier();
		put_CE_to_json(obsCE, labresult_json);
			
		// Add Value and Unit if appropriate.
		String valueType = obsResult.getValueType().getValueOrEmpty();
		if (!valueType.isEmpty()) {
//			if (valueType.equalsIgnoreCase("NM") 
//					|| valueType.equalsIgnoreCase("ST")
//					|| valueType.equalsIgnoreCase("TX")) {
			int totalValues = obsResult.getObservationValueReps();
			if (totalValues > 0) {
				Varies obsValue = obsResult.getObservationValue(0);
				labresult_json.put("Value", obsValue.getData());
				CE unitCE = obsResult.getUnits();
				if (unitCE != null) {
					JSONObject unit_json = new JSONObject();
					if (put_CE_to_json(unitCE, unit_json) == 0)
						labresult_json.put("Unit", unit_json);
				}
			}
//			}
		}
		
		// Put date: Not required by ECR. But, putting the date anyway...
		TS obxDate = obsResult.getDateTimeOfTheObservation();
		if (obxDate != null) {
			labresult_json.put("Date", obxDate.getTime().getValue());
		}
		
		return labresult_json;
	}

	private int set_sending_application(ORU_R01 msg, JSONObject ecr_json) {
		String namespaceID = "";
		String universalID = "";
		String universalIDType = "";
		
		MSH msh = msg.getMSH();
		HD sendingAppHD = msh.getSendingApplication();
		if (sendingAppHD != null) {
			namespaceID = sendingAppHD.getNamespaceID().getValueOrEmpty();
			universalID = sendingAppHD.getUniversalID().getValueOrEmpty();
			universalIDType = sendingAppHD.getUniversalIDType().getValueOrEmpty();
		}
		
		String sendingApp = "";
		if (!namespaceID.isEmpty())
			sendingApp = namespaceID+" ";
		if (!universalID.isEmpty())
			sendingApp += universalID+" ";
		if (!universalIDType.isEmpty())
			sendingApp += universalIDType;
		
		sendingApp = sendingApp.trim();
		
		ecr_json.put("Sending Application", sendingApp);
		
		return 0;
	}
	
	private ErrorCode map2ecr(ORU_R01 msg) {
		// Mapping ELR message to ECR.
		//
		// Investigate Patient Result Group
		// see http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7%20v2.5.1&triggerEvent=ORU_R01
		//
		// There can be multiple Patient Results. We send ECR per patient.
		int newECRs = 0;
		int totalRepPatientResult = msg.getPATIENT_RESULTReps();
		for (int i=0; i<totalRepPatientResult; i++) {
			// Create a new empty ECR JSON.
			JSONObject ecr_json = new JSONObject();
			
			// Set sending application.
			int res = set_sending_application(msg, ecr_json);
			if (res < 0) {
				return ErrorCode.MSH;
				
			}

			// Patient specific information
			ORU_R01_PATIENT_RESULT patient_result = msg.getPATIENT_RESULT(i);
			ORU_R01_PATIENT patient = patient_result.getPATIENT();
			int result = map_patient (patient, ecr_json);
			if (result >= 0)
				newECRs = newECRs+result;
			else if (result < 0) {
				return ErrorCode.PID;
			}
			
			// We should have the patient populated.
			JSONObject patient_json;
			if (ecr_json.isNull("Patient")) {
				// This means the HL7v2 message has no patient demographic information.
				// This shouldn't happen. But, anything can happen in the real world. So,
				// we don't stop here. We are moving on.
				patient_json = new JSONObject();
				ecr_json.put("Patient", patient_json);
			} else {
				patient_json = ecr_json.getJSONObject("Patient");
			}

			// ORC/OBR Parsing
			JSONArray laborders_json = new JSONArray();
			
			// This is a new JSON Array object. Put it in the patient section.
			patient_json.put("Lab_Order_Code", laborders_json);

			int totalOrderObs = msg.getPATIENT_RESULT(i).getORDER_OBSERVATIONReps();
			for (int j=0; j<totalOrderObs; j++) {
				ORU_R01_ORDER_OBSERVATION orderObs = patient_result.getORDER_OBSERVATION(j);
				JSONObject laborder_json = map_order_observation (orderObs);
				if (laborder_json == null) {
					return ErrorCode.ORDER_OBSERVATION;
				}
				laborders_json.put(laborder_json);
				
				// We add lab results to lab order.
				JSONArray labresults_json = new JSONArray();
				laborder_json.put("Laboratory_Results", labresults_json);
				int totalObservations = orderObs.getOBSERVATIONReps();
				for (int k=0; k<totalObservations; k++) {
					OBX obsResult = orderObs.getOBSERVATION(k).getOBX();
					JSONObject labresult_json = map_lab_result (obsResult);
					if (labresult_json == null) {
						return ErrorCode.LAB_RESULTS;
					}
					labresults_json.put(labresult_json);
				}
				
				// For each order, we have provider, facility, order date and reason information.
				// We put this information in the high level.
				//
				// Provider and Facility at Top ECR level.
				// Order Date and Reason at Patient level.
				//
				// Provider and Facility: 
				// We are in the Order Loop. So, we will come back. However, 
				// ECR allows only one provider and facility. So, this can be overwritten
				// by next order if provider info exists.
				if (!laborder_json.isNull("Provider")) {
					JSONObject provider_json = laborder_json.getJSONObject("Provider");
					if (provider_json != null) ecr_json.put("Provider", provider_json);
				}
				
				if (!laborder_json.isNull("Facility")) {
					JSONObject facility_json = laborder_json.getJSONObject("Facility");
					if (facility_json != null) ecr_json.put("Facility", facility_json);
				}
				
				// Order Date and Reason. 
				// We have Visit DateTime in ECR. We will put order date as a visit date
				// as the order usually made when a patient visits a clinic.
				if (!laborder_json.isNull("DateTime")) {
					JSONObject orderDate_json = laborder_json.getJSONObject("DateTime");
					if (orderDate_json != null) patient_json.put("Visit_DateTime", orderDate_json);
				}
				
				// We have reasons in lab order. We put this in the trigger code.
				if (!laborder_json.isNull("Reasons")) {
					JSONArray reasons_json = laborder_json.getJSONArray("Reasons");
					JSONArray triggercode_json;
					if (patient_json.isNull("Tigger_Code")) {
						triggercode_json = new JSONArray();
						patient_json.put("Trigger_Code", triggercode_json);
					} else {
						triggercode_json = patient_json.getJSONArray("Trigger_Code");
					}
					if (reasons_json != null) {
						for (int c=0; c<reasons_json.length(); c++) {
							triggercode_json.put(reasons_json.get(c));
						}
					}
				}
			}
						
			try {
				send_ecr (ecr_json);
			} catch (Exception e) {
				e.printStackTrace();
				return ErrorCode.INTERNAL;
			}
		}		
		
		return ErrorCode.NOERROR;
	}
	
	public int process_q() {
		String ecr = "";
		int ret = 0;
		if (queueFile.isEmpty()) return ret;
		boolean success = true;
		try {
			byte[] data = queueFile.peek();
			queueFile.remove();
			ecr = new String (data, StandardCharsets.UTF_8);
			System.out.println("ecr from queue("+queueFile.size()+"):"+ecr);
			JSONObject ecrJson = new JSONObject (ecr);
			send_ecr(ecrJson);
		} catch (JSONException e) {
			success = false;
			// We have ill-formed JSON. Remove it from queue.
			LOGGER.error("Failed to process JSON data in Queue: "+e.getMessage()+"\nJSON data:"+ecr);
			e.printStackTrace();
		} catch (Exception e) {
			success = false;
			e.printStackTrace();
		}
		
		if (success) {
			ret = queueFile.size();
		} else {
			ret = -1;
		}
		
		return ret;
	}
	
	private void send_ecr(JSONObject ecrJson) 
		throws Exception {

//		System.out.println("ECR Report submitted:"+ecrJson.toString());
//		return;
//Todo: Deal with this later. Just add ID for now.
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		Long id_num = (Long) (timestamp.getTime()/10000);
		ecrJson.put("id", id_num.toString());
		
		Client client = Client.create();
		WebResource webResource = client.resource(phcr_controller_api_url);
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecrJson.toString());
		if (response.getStatus() != 201) {
			// Failed to write ECR. We should put this in the queue and retry.
			LOGGER.error("Failed to talk to PHCR controller for ECR Resport:\n"+ecrJson.toString());
			System.out.println("Failed to talk to PHCR controller:"+ecrJson.toString());
			queueFile.add(ecrJson.toString().getBytes());
			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
		} else {
			LOGGER.info("ECR Report submitted:"+ecrJson.toString());
			System.out.println("ECR Report submitted:"+ecrJson.toString());
		}
	}
}
