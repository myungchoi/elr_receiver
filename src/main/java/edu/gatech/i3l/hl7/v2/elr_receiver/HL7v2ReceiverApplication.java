package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONException;
import org.json.JSONObject;

import com.squareup.tape2.QueueFile;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.primitive.IS;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.FN;
import ca.uhn.hl7v2.model.v251.datatype.HD;
import ca.uhn.hl7v2.model.v251.datatype.ST;
import ca.uhn.hl7v2.model.v251.datatype.TS;
import ca.uhn.hl7v2.model.v251.datatype.XAD;
import ca.uhn.hl7v2.model.v251.datatype.XPN;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
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
	private JSONObject ecr_json = null;
	
	// Error Status
	static int PID_ERROR = -1;
	static enum ErrorCode {
		NOERROR, PID;
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
		
		// Set up ECR template
		if (ecr_json == null) {
			ecr_json = parseJSONFile(ecr_template_filename);
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
		if (theMessage.getVersion().equalsIgnoreCase("2.5.1") == false)
			return false;
		
		// Check the message type
		Terser t = new Terser(theMessage);
		try {
			if (t.get("/MSH-9-1").equalsIgnoreCase("ORU") == false 
					|| t.get("/MSH-9-2").equalsIgnoreCase("R01") == false
					|| t.get("/MSH-9-3").equalsIgnoreCase("ORU_R01") == false)
				return false;
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
			send_ecr(ecr_json);
			return theMessage.generateACK();
		} catch (IOException e) {
			throw new HL7Exception(e);
		} catch (Exception e) {
			throw new ReceivingApplicationException(e);
		}
	}

	/*
	 * From Message, parse patient information and map to ECR 
	 * 
	 * We walk through Patient Results and selects the patient ID that has assigned auth
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
	 * returns 0 if successful without error.
	 */
	private int mapPatientInfo (ORU_R01 msg) {
		String selectedPatientID = "";
		String patientID = "";
		String patientName_last = "";
		String patientName_given = "";
		String patientName_middle = "";
		String patientDOB = "";
		String patientGender = "";
		String patientRaceSystem = "";
		String patientRaceCode = "";
		String patientRaceDisplay = "";
		String patientAddress = "";
		String patientPreferredLanguageSystem = "";
		String patientPreferredLanguageCode = "";
		String patientPreferredLanguageDisplay = "";
		String patientEthnicitySystem = "";
		String patientEthnicityCode = "";
		String patientEthnicityDisplay = "";
				
		int totalRepPatientResult = msg.getPATIENT_RESULTReps();
		for (int i=0; i<totalRepPatientResult; i++) {
			ORU_R01_PATIENT patient = msg.getPATIENT_RESULT(i).getPATIENT();
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
				if (AssignAuthName.isEmpty()) {
					// We need to have this...
					continue;
				}
				
				if (AssignAuthName.equalsIgnoreCase("EMR")) {
					selectedPatientID = patientID;
					break;
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
			
			// DOB parse.
			TS dateTimeDOB = pid_seg.getDateTimeOfBirth();
			try {
				if (dateTimeDOB.isEmpty() == false) {
					patientDOB = dateTimeDOB.getTime().getValue();
				}
			} catch (HL7Exception e) {
				e.printStackTrace();
			}
			
			// Administrative Sex
			IS gender = pid_seg.getAdministrativeSex();
			patientGender = gender.getValueOrEmpty();

			// Race
			int totRaces = pid_seg.getRaceReps();
			for (int j=0; j<totRaces; j++) {
				CE raceCodedElement = pid_seg.getRace(j);
				patientRaceSystem = raceCodedElement.getNameOfCodingSystem().getValueOrEmpty();
				patientRaceCode = raceCodedElement.getIdentifier().getValueOrEmpty();
				patientRaceDisplay = raceCodedElement.getText().getValueOrEmpty();
				if (patientRaceSystem.isEmpty() && patientRaceCode.isEmpty() && patientRaceDisplay.isEmpty()) {
					// Before we move to the next race. Check if we have alternative system.
					patientRaceSystem = raceCodedElement.getNameOfAlternateCodingSystem().getValueOrEmpty();
					patientRaceCode = raceCodedElement.getAlternateIdentifier().getValueOrEmpty();
					patientRaceDisplay = raceCodedElement.getAlternateText().getValueOrEmpty();
					if (patientRaceSystem.isEmpty() && patientRaceCode.isEmpty() && patientRaceDisplay.isEmpty()) 
						continue;
				}
				
				// We should have at least one Race Coded Element. Break out and move on
				break;
			}
			
			// Address
			int totAddresses = pid_seg.getPatientAddressReps();
			for (int j=0; j<totAddresses; j++) {
				XAD addressXAD = pid_seg.getPatientAddress(j);
				patientAddress = addressXAD.getStreetAddress().getStreetOrMailingAddress().getValueOrEmpty();
			}
			
			// Preferred Language
			CE primaryLangCE = pid_seg.getPrimaryLanguage();
			patientPreferredLanguageSystem = primaryLangCE.getNameOfCodingSystem().getValueOrEmpty();
			patientPreferredLanguageCode = primaryLangCE.getIdentifier().getValueOrEmpty();
			patientPreferredLanguageDisplay = primaryLangCE.getText().getValueOrEmpty();
			
			if (patientPreferredLanguageSystem.isEmpty() && patientPreferredLanguageCode.isEmpty() && patientPreferredLanguageDisplay.isEmpty()) {
				patientPreferredLanguageSystem = primaryLangCE.getCe6_NameOfAlternateCodingSystem().getValueOrEmpty();
				patientPreferredLanguageCode = primaryLangCE.getAlternateIdentifier().getValueOrEmpty();
				patientPreferredLanguageDisplay = primaryLangCE.getAlternateText().getValueOrEmpty();
			}
			
			// Ethnicity
			int totEthnicity = pid_seg.getEthnicGroupReps();
			for (int j=0; j<totEthnicity; j++) {
				CE ethnicityCE = pid_seg.getEthnicGroup(j);
				patientEthnicitySystem = ethnicityCE.getNameOfCodingSystem().getValueOrEmpty();
				patientEthnicityCode = ethnicityCE.getIdentifier().getValueOrEmpty();
				patientEthnicityDisplay = ethnicityCE.getText().getValueOrEmpty();
				
				if (patientEthnicitySystem.isEmpty() && patientEthnicityCode.isEmpty() && patientEthnicityDisplay.isEmpty()) {
					patientEthnicitySystem = ethnicityCE.getNameOfAlternateCodingSystem().getValueOrEmpty();
					patientEthnicityCode = ethnicityCE.getAlternateIdentifier().getValueOrEmpty();
					patientEthnicityDisplay = ethnicityCE.getAlternateText().getValueOrEmpty();
				}
			}
			
			// We are done for parsing. If we have required fields, then break out.
			// Otherwise, loop to another Patient Result.
			if (selectedPatientID.isEmpty() == false 
					&& (patientName_given.isEmpty() == false || patientName_last.isEmpty() == false))
				break;
		}
		
		if (patientID.isEmpty() || (patientName_given.equalsIgnoreCase("") && patientName_last.equalsIgnoreCase(""))) {
			// We have no patient ID or no patient name information. Return with null.
			return -1;
		}

		if (selectedPatientID.isEmpty()) {
			selectedPatientID = patientID;
		}
		
		// Set all the collected patient information to ECR
		JSONObject patient_json = ecr_json.getJSONObject("Patient");
		JSONObject patient_name = patient_json.getJSONObject("Name");
		
		patient_json.put("ID", selectedPatientID);
		patient_name.put("given", patientName_given);
		patient_name.put("family", patientName_last);
		
		// Patient DOD
		patient_json.put("Birth Date", patientDOB);
		
		// Administrative Gender
		patient_json.put("Sex", patientGender);
		
		// Adding Race Elements....
		JSONObject patient_race = patient_json.getJSONObject("Race");
		patient_race.put("System", patientRaceSystem);
		patient_race.put("Code", patientRaceCode);
		patient_race.put("Display", patientRaceDisplay);
		
		// Add Address
		patient_json.put("Street Address", patientAddress);
		
		// Add Preferred Language
		JSONObject patient_preferred_lang = patient_json.getJSONObject("Preferred Language");
		patient_preferred_lang.put("System", patientPreferredLanguageSystem);
		patient_preferred_lang.put("Code", patientPreferredLanguageCode);
		patient_preferred_lang.put("Display", patientPreferredLanguageDisplay);
		
		// Add Ethnicity
		JSONObject patient_ethnicity = patient_json.getJSONObject("Ethnicity");
		patient_ethnicity.put("System", patientEthnicitySystem);
		patient_ethnicity.put("Code", patientEthnicityCode);
		patient_ethnicity.put("Display", patientEthnicityDisplay);
		
		return 0;
	}
	
	private ErrorCode map2ecr(ORU_R01 msg) {
		// Mapping ELR message to ECR.
		//
		// Investigate Patient Result Group
		// see http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7%20v2.5.1&triggerEvent=ORU_R01
		//
		if (mapPatientInfo(msg) < 0) {
			// We have no complete patient information.
			// Log this.
			return ErrorCode.PID;
		}
		
		
		
		return ErrorCode.NOERROR;
	}
	
	public int process_q() {
		int ret = 0;
		if (queueFile.isEmpty()) return ret;
		boolean success = true;
		try {
			byte[] data = queueFile.peek();
			String ecr = data.toString();
			JSONObject ecrJson = new JSONObject (ecr);
			send_ecr(ecrJson);
		} catch (IOException e) {
			success = false;
			e.printStackTrace();
		} catch (Exception e) {
			success = false;
			e.printStackTrace();
		}
		
		if (success) {
			ret = 1;
			try {
				queueFile.remove();
				ret = queueFile.size();
			} catch (IOException e) {
				ret = -1;
				e.printStackTrace();
			}
		} else {
			ret = -1;
		}
		
		return ret;
	}
	
	private void send_ecr(JSONObject ecrJson) 
		throws Exception {
		
		Client client = Client.create();
		WebResource webResource = client.resource(phcr_controller_api_url);
		
		System.out.println(ecrJson.toString());
//		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecr_json);
//		if (response.getStatus() != 201) {
//			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
//		} else {
//			// Failed to write ECR. We should put this in the queue and retry.
//			queueFile.add(ecrJson.toString().getBytes());
//		}		
	}
}
