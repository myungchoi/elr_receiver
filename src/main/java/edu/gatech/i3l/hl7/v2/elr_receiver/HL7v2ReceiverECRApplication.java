package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v231.group.ORU_R01_ORCOBRNTEOBXNTECTI;
import ca.uhn.hl7v2.model.v231.group.ORU_R01_PIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.util.Terser;
import edu.gatech.i3l.hl7.v2.parser.ecr.BaseHL7v2ECRParser;
import edu.gatech.i3l.hl7.v2.parser.ecr.HL7v231ECRParser;
import edu.gatech.i3l.hl7.v2.parser.ecr.HL7v251ECRParser;

/*
 * HL7v2 Message Receiver Application for ELR
 * 
 * Author : Myung Choi (myung.choi@gtri.gatech.edu)
 * Version: 0.1-beta
 * 
 * Implementation Guide: V251_IG_LB_LABRPTPH_R2_DSTU_R1.1_2014MAY.PDF (Available from HL7.org)
 */

public class HL7v2ReceiverECRApplication<v extends BaseHL7v2ECRParser> extends HL7v2ReceiverApplication<v> {
	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v2ReceiverECRApplication.class.getName());
		
//	public static JSONObject parseJSONFile(String filename) throws JSONException, IOException {
//        String content = new String(Files.readAllBytes(Paths.get(filename)));
//        return new JSONObject(content);
//    }
	
	@Override
	public boolean canProcess(Message theMessage) {
		// We accepts when the follow conditions met.
		// - MSH-21 Message Profile Identifier: We need to talk to Lab (eg Labcorp) to make sure
		//          that we use correct profile. ELR document has the following in the example
		//          LRI_Common_Component^^2.16.840.1.113883.9 .16^ISO~LRI_GU_Component^^2.16.840.1.113883.9.12^ISO~LAB_RU_Componen t^^2.16.840.1.113883.9.14^ISO
		// - MSH-12.1: 2.5.1 or 2.3.1  We support HL7 v2.5.1 or v2.3.1
		// - MSH-9(1.2.3): ORU^R01^ORU_R01 messages (in 1.10.1)
		
		// Check MSH-21 for the message profile
		// TODO: Implement this after discussing with LabCorp
		
		// Check the version = v2.5.1 or v2.3.1
		
		if (theMessage.getVersion().equalsIgnoreCase("2.5.1") == true) {
			setMyParser((v)new HL7v251ECRParser());
		} else if (theMessage.getVersion().equalsIgnoreCase("2.3.1") == true) {
			setMyParser((v) new HL7v231ECRParser());
		} else {
			LOGGER.error("Message Received, but neither v2.5.1 nor v2.3.1. Received message version is "+theMessage.getVersion());
		}
		
		// // Check the message type
		// Terser t = new Terser(theMessage);
		// try {
		// 	String MSH91 = t.get("/MSH-9-1");
		// 	String MSH92 = t.get("/MSH-9-2");
		// 	String MSH93 = t.get("/MSH-9-3");
			
		// 	if ((MSH91 != null && MSH91.equalsIgnoreCase("ORU") == false) 
		// 			|| (MSH92 != null && MSH92.equalsIgnoreCase("R01") == false)
		// 			|| (MSH93 != null && MSH93.equalsIgnoreCase("ORU_R01") == false)) {
		// 		LOGGER.info("Message with correct version received, but not ORU_R01 message type. Receved message type: "+t.get("/MSH-9-1")+" "+t.get("/MSH-9-2")+" "+t.get("/MSH-9-3"));
		// 		return false;
		// 	}
		// } catch (HL7Exception e) {
		// 	e.printStackTrace();
		// 	return false;
		// }
		
		return true;
	}
	
	@Override
	protected ErrorCode mapMyMessage(Message msg) {
		// Mapping ELR message to ECR.
		//
		// Investigate Patient Result Group
		// see http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7%20v2.5.1&triggerEvent=ORU_R01
		//
		// There can be multiple Patient Results. We send ECR per patient.
					
		int newECRs = 0;
		int totalRepPatientResult;
		if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1"))
			totalRepPatientResult = ((ca.uhn.hl7v2.model.v231.message.ORU_R01)msg).getPIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTIReps();
		else
			totalRepPatientResult = ((ca.uhn.hl7v2.model.v251.message.ORU_R01)msg).getPATIENT_RESULTReps();
			
		for (int i=0; i<totalRepPatientResult; i++) {
			// Create a new empty ECR JSON.
			JSONObject ecr_json = new JSONObject();
			
			// Set sending application.
			int res = getMyParser().map_provider_from_appfac ((Object) msg, ecr_json);
			if (res != 0) {
				return ErrorCode.MSH;
			}

			// Patient specific information
			Object patient;
			if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
				ORU_R01_PIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI patient_result = ((ca.uhn.hl7v2.model.v231.message.ORU_R01)msg).getPIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI(i);
				patient = patient_result.getPIDPD1NK1NTEPV1PV2();
			} else {
				ORU_R01_PATIENT_RESULT patient_result = ((ca.uhn.hl7v2.model.v251.message.ORU_R01)msg).getPATIENT_RESULT(i);
				patient = patient_result.getPATIENT();
			}
			
			int result = getMyParser().map_patient (patient, ecr_json);
			if (result == 0) {
				newECRs++;
			} else {
				// return ErrorCode.PID;
				continue;
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

			int totalOrderObs;
			if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
				ORU_R01_PIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI patient_result = ((ca.uhn.hl7v2.model.v231.message.ORU_R01)msg).getPIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI(i);
				totalOrderObs = patient_result.getORCOBRNTEOBXNTECTIReps();
			} else {
				ORU_R01_PATIENT_RESULT patient_result = ((ca.uhn.hl7v2.model.v251.message.ORU_R01)msg).getPATIENT_RESULT(i);
				totalOrderObs = patient_result.getORDER_OBSERVATIONReps();
			}
			for (int j=0; j<totalOrderObs; j++) {
				Object orderObs;
				if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
					ORU_R01_PIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI patient_result = ((ca.uhn.hl7v2.model.v231.message.ORU_R01)msg).getPIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI(i);
					orderObs = patient_result.getORCOBRNTEOBXNTECTI(j);
				} else {
					ORU_R01_PATIENT_RESULT patient_result = ((ca.uhn.hl7v2.model.v251.message.ORU_R01)msg).getPATIENT_RESULT(i);
					orderObs = patient_result.getORDER_OBSERVATION(j);
				}

				JSONObject laborder_json = getMyParser().map_order_observation (orderObs);
				if (laborder_json == null) {
					return ErrorCode.ORDER_OBSERVATION;
				}
				laborders_json.put(laborder_json);
				
				// We add lab results to lab order.
				JSONArray labresults_json = new JSONArray();
				laborder_json.put("Laboratory_Results", labresults_json);
				
				int totalObservations;
				if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
					totalObservations = ((ORU_R01_ORCOBRNTEOBXNTECTI)orderObs).getOBXNTEReps();
				} else {
					totalObservations = ((ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATIONReps();
				}
				for (int k=0; k<totalObservations; k++) {
					Object obsResult;
					if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
						obsResult = ((ORU_R01_ORCOBRNTEOBXNTECTI)orderObs).getOBXNTE(k).getOBX();
					} else {
						obsResult = ((ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATION(k).getOBX();						
					}
					JSONObject labresult_json = getMyParser().map_lab_result (obsResult);
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
					if (provider_json != null) 
						getMyParser().add_provider (provider_json, ecr_json);
				}				
				
				if (!laborder_json.isNull("Facility")) {
					JSONObject facility_json = laborder_json.getJSONObject("Facility");
					if (facility_json != null) ecr_json.put("Facility", facility_json);
				}
				
				// Order Date and Reason. 
				// We have Visit DateTime in ECR. We will put order date as a visit date
				// as the order usually made when a patient visits a clinic.
				if (!laborder_json.isNull("DateTime")) {
					String orderDate_json = laborder_json.getString("DateTime");
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
				sendEcr (ecr_json);
			} catch (Exception e) {
				e.printStackTrace();
				return ErrorCode.INTERNAL;
			}
		}		
		
		if (newECRs == 0) {
			return ErrorCode.PID;
		}
		
		return ErrorCode.NOERROR;
	}
	
	private void sendEcr(JSONObject ecrJson) 
		throws Exception {

//		System.out.println("ECR Report submitted:"+ecrJson.toString());
//		return;
		
//Todo: Deal with this later. Just add ID for now.
		
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		Long id_num = (Long) (timestamp.getTime()/10000);
		ecrJson.put("id", id_num.toString());
		
		LOGGER.debug("Sending to "+getControllerApiUrl()+": "+ecrJson.toString());
		Client client = Client.create();
		WebResource webResource = client.resource(getControllerApiUrl());
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecrJson.toString());
		if (response.getStatus() != 201 && response.getStatus() != 200) {
			// Failed to write ECR. We should put this in the queue and retry.
			LOGGER.error("Failed to talk to PHCR controller ("+getControllerApiUrl()+") for ECR Resport:\n"+ecrJson.toString());
			System.out.println("Failed to talk to PHCR controller ("+getControllerApiUrl()+"):"+ecrJson.toString());
			getQueueFile().add(ecrJson.toString().getBytes());
			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
		} else {
			LOGGER.info("ECR Report submitted:"+ecrJson.toString());
			System.out.println("ECR Report submitted:"+ecrJson.toString());
		}
	}

	public void sendData(JSONObject jsonData) {
		try {
			sendEcr(jsonData);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
}
