package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
	
	private void put_CE_to_json (CE element, JSONObject json_obj) {
		String System = element.getNameOfCodingSystem().getValueOrEmpty();
		String Code = element.getIdentifier().getValueOrEmpty();
		String Display = element.getText().getValueOrEmpty();
		
		if (System.isEmpty() && Code.isEmpty() && Display.isEmpty()) {
			json_obj.put("System", element.getNameOfAlternateCodingSystem().getValueOrEmpty());
			json_obj.put("Code", element.getAlternateIdentifier().getValueOrEmpty());
			json_obj.put("Display", element.getAlternateText().getValueOrEmpty());
		} else {
			json_obj.put("System", System);
			json_obj.put("Code", Code);
			json_obj.put("Display", Display);					
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
				patient_json.put("Birth Date", dateTimeDOB.getTime().getValue());
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
		int weight = 0;
		for (int j=0; j<totAddresses; j++) {
			XAD addressXAD = pid_seg.getPatientAddress(j);
			String address = make_single_address (pid_seg.getPatientAddress(j));
			if (address == "") continue;
			patient_json.put("Street Address", address);
			break;
		}
		
		// Preferred Language
		JSONObject patient_preferred_lang = new JSONObject();
		patient_json.put("Preferred Language", patient_preferred_lang);
		
		CE primaryLangCE = pid_seg.getPrimaryLanguage();
		put_CE_to_json(primaryLangCE, patient_preferred_lang);
		
		// Ethnicity
		JSONObject patient_ethnicity = new JSONObject();
		patient_json.put("Ethnicity", patient_ethnicity);
		
		int totEthnicity = pid_seg.getEthnicGroupReps();
		for (int j=0; j<totEthnicity; j++) {
			CE ethnicityCE = pid_seg.getEthnicGroup(j);
			put_CE_to_json(ethnicityCE, patient_ethnicity);
		}
		
		return 1;
	}

	/*
	 * 		 * ORDER OBSERVATION List
	 *   OBR-16. Use ORC-12 if OBR-16 is empty.
	 */
	private int map_order_observation (ORU_R01_ORDER_OBSERVATION orderObs, JSONObject ecr_json) {
		int ret = 0;
		// Get patient json object.
		JSONObject patient_json;
		if (ecr_json.isNull("Patient")) {
			patient_json = new JSONObject();
			ecr_json.put("Patient", patient_json);
		} else {
			patient_json = ecr_json.getJSONObject("Patient");
		}
	
		// Provider
		JSONObject provider_json = new JSONObject();
		ecr_json.put("Provider", provider_json);
		
		OBR orderRequest = orderObs.getOBR();
		int totalObr16 = orderRequest.getOrderingProviderReps();
		for (int i=0; i<totalObr16; i++) {
			XCN orderingProviderXCN = orderRequest.getOrderingProvider(i);
			
			String orderingProviderID = orderingProviderXCN.getIDNumber().getValueOrEmpty();
			if (!orderingProviderID.isEmpty()) {
				provider_json.put("ID", orderingProviderID);
				ret = 1;
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
				ret = 1;
			}
			
			if (!orderingProviderID.isEmpty() && !orderingProviderName.isEmpty()) {
				break;
			}
		}
		
		// Observation Time
		String observationTime = orderRequest.getObservationDateTime().getTime().getValue();
		patient_json.put("Visit DateTime", observationTime);
		
		// Reason for Study		
		JSONArray reasons_json = new JSONArray();
		patient_json.put("Trigger Code", reasons_json);
		
		int totalReasons = orderRequest.getReasonForStudyReps();
		for (int i=0; i<totalReasons; i++) {
			CE reasonCE = orderRequest.getReasonForStudy(i);
			JSONObject reason_json = new JSONObject();
			put_CE_to_json (reasonCE, reason_json);
			reasons_json.put(reason_json);
		}
		
		// Below is ORC ---
		// Facility
		JSONObject facility_json = new JSONObject();
		ecr_json.put("Facility", facility_json);
		
		ORC common_order = orderObs.getORC();
		
		// Facility Name
		int totalFacilityNames = common_order.getOrderingFacilityNameReps();
		for (int i=0; i<totalFacilityNames; i++) {
			XON orderFacilityXON = common_order.getOrderingFacilityName(i);
			String orgName = orderFacilityXON.getOrganizationName().getValueOrEmpty();
			if (!orgName.isEmpty()) {
				facility_json.put("Name", orgName);
				String orgID = orderFacilityXON.getIDNumber().getValue();
				if (orgID != null) {
					ret ++;
					facility_json.put("ID", orgID);
					break;
				}
			}
		}
		
		// Facility Phone Number
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
			facility_json.put("Phone", phone);
			if (!country.isEmpty() && !area.isEmpty() && !local.isEmpty()) {
				ret++;
				break;
			} else {
				String backward_phone = common_order.getOrderingFacilityPhoneNumber(i).getTelephoneNumber().getValueOrEmpty();
				if (!backward_phone.isEmpty()) {
					facility_json.put("Phone", backward_phone);
					ret++;
					break;
				}
			}
		}
		
		// Facility Address
		int totalFacilityAddress = common_order.getOrderingProviderAddressReps();
		for (int i=0; i<totalFacilityAddress; i++) {
			XAD addressXAD = common_order.getOrderingProviderAddress(i);
			String address = make_single_address (addressXAD);
			if (address=="") continue;
			facility_json.put("Address", address);
			ret++;
			break;
		}
		
		return ret;
	}
	
	private int map_lab_results(ORU_R01_ORDER_OBSERVATION orderObs, JSONObject ecr_json) {
		int ret = 0;

		JSONObject patient_json;
		if (ecr_json.isNull("Patient")) {
			patient_json = new JSONObject();
			ecr_json.put("Patient", patient_json);
		} else {
			patient_json = ecr_json.getJSONObject("Patient");
		}
		
		// Below is OBSERVATION Section, which contains OBX. Laboratory Results
		JSONArray labResults_json;
		if (patient_json.isNull("Laboratory Results")) {
			labResults_json = new JSONArray();
			patient_json.put("Laboratory Results", labResults_json);
		} else {
			labResults_json = patient_json.getJSONArray("Laboratory Results");
		}
			
		int totalObservations = orderObs.getOBSERVATIONReps();
		for (int i=0; i<totalObservations; i++) {
			JSONObject labResult_json = new JSONObject();
			// get OBX
			OBX obsResultOBX = orderObs.getOBSERVATION(i).getOBX();
			CE obsCE = obsResultOBX.getObservationIdentifier();
			put_CE_to_json(obsCE, labResult_json);
			
			// Add Value and Unit if appropriate.
			String valueType = obsResultOBX.getValueType().getValueOrEmpty();
			if (!valueType.isEmpty()) {
				if (valueType.equalsIgnoreCase("NM") 
						|| valueType.equalsIgnoreCase("ST")
						|| valueType.equalsIgnoreCase("TX")) {
					int totalValues = obsResultOBX.getObservationValueReps();
					if (totalValues > 0) {
						Varies obsValue = obsResultOBX.getObservationValue(0);
						labResult_json.put("Value", obsValue.toString());
						CE unitCE = obsResultOBX.getUnits();
						if (unitCE != null) {
							JSONObject unit_json = new JSONObject();
							put_CE_to_json(unitCE, unit_json);
							labResult_json.put("Unit", unit_json);
						}
					}
				}
			}
			
			// Put date.
			TS obxDate = obsResultOBX.getDateTimeOfTheObservation();
			if (obxDate != null) {
				labResult_json.put("Date", obxDate.getTime().getValue());
			}
			
			labResults_json.put(labResult_json);
			ret++;
		}
		
		return ret;
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
		JSONObject ecr_json = new JSONObject();
		
		// Set sending application.
		int res = set_sending_application(msg, ecr_json);
		if (res < 0) {
			return ErrorCode.MSH;
			
		}
		int newECRs = 0;
		int totalRepPatientResult = msg.getPATIENT_RESULTReps();
		for (int i=0; i<totalRepPatientResult; i++) {
			// Patient specific information
			ORU_R01_PATIENT patient = msg.getPATIENT_RESULT(i).getPATIENT();
			int result = map_patient (patient, ecr_json);
			if (result >= 0)
				newECRs = newECRs+result;
			else if (result < 0) {
				return ErrorCode.PID;
			}
			
			// Provider
			int totalOrderObs = msg.getPATIENT_RESULT(i).getORDER_OBSERVATIONReps();
			for (int j=0; j<totalOrderObs; j++) {
				ORU_R01_ORDER_OBSERVATION orderObs = msg.getPATIENT_RESULT(i).getORDER_OBSERVATION(j);
				result = map_order_observation (orderObs, ecr_json);
				if (result > 0) break;
				else if (result < 0) {
					return ErrorCode.ORDER_OBSERVATION;
				}
			}
			
			// LabResults
			for (int j=0; j<totalOrderObs; j++) {
				ORU_R01_ORDER_OBSERVATION orderObs = msg.getPATIENT_RESULT(i).getORDER_OBSERVATION(j);
				result = map_lab_results (orderObs, ecr_json);
				if (result < 0) {
					return ErrorCode.LAB_RESULTS;
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
		
		Client client = Client.create();
		WebResource webResource = client.resource(phcr_controller_api_url);
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecrJson.toString());
		if (response.getStatus() != 201) {
			// Failed to write ECR. We should put this in the queue and retry.
			LOGGER.error("Failed to talk to PHCR controller for ECR Resport:\n"+ecrJson.toString());
//			System.out.println("Failed to talk to PHCR controller:"+ecrJson.toString());
			queueFile.add(ecrJson.toString().getBytes());
			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
		} 
	}
}
