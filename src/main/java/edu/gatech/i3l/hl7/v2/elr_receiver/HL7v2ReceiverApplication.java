package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

// uncomment below
//import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.tape2.QueueFile;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.hoh.api.IAuthorizationServerCallback;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import edu.gatech.i3l.hl7.v2.parser.BaseHL7v2Parser;

/*
 * HL7v2 Message Receiver Application for ELR
 * 
 * Author : Myung Choi (myung.choi@gtri.gatech.edu)
 * Version: 0.1-beta
 * 
 * Implementation Guide: V251_IG_LB_LABRPTPH_R2_DSTU_R1.1_2014MAY.PDF (Available from HL7.org)
 */

public abstract class HL7v2ReceiverApplication<v extends BaseHL7v2Parser>
		implements IHL7v2ReceiverApplication, IAuthorizationServerCallback {
	private String controller_api_url;
	private boolean useTls;
	private QueueFile queueFile = null;
	private TimerTask timerTask = null;
	private Timer timer = null;
	private v myParser = null;
	private String httpUser = null;
	private String httpPw = null;
	private String indexServiceApiUrl;

	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v2ReceiverApplication.class.getName());

	// Error Status
	static int PID_ERROR = -1;

	static enum ErrorCode {
		NOERROR, MSH, PID, ORDER_OBSERVATION, LAB_RESULTS, INTERNAL;
	}

//	public static JSONObject parseJSONFile(String filename) throws JSONException, IOException {
//        String content = new String(Files.readAllBytes(Paths.get(filename)));
//        return new JSONObject(content);
//    }

	public void setMyParser(v myParser) {
		this.myParser = myParser;
	}

	public v getMyParser() {
		return myParser;
	}

	public QueueFile getQueueFile() {
		return queueFile;
	}

	public String getControllerApiUrl() {
		return controller_api_url;
	}
	
	public String getIndexServiceApiUrl() {
		return indexServiceApiUrl;
	}

	public void config(String controller_api_url, boolean useTls, String qFileName, String ecr_template_filename,
			String httpAuth, String indexServiceApiUrl) throws Exception {

		this.controller_api_url = controller_api_url;
		this.indexServiceApiUrl = indexServiceApiUrl;
		this.useTls = useTls;

		if (httpAuth != null && !httpAuth.isEmpty()) {
			String[] httpAuthParam = httpAuth.split(":");
			if (httpAuthParam.length == 2) {
				this.httpUser = httpAuthParam[0];
				this.httpPw = httpAuthParam[1];
			} else {
				LOGGER.error(
						"Failed to load HTTP Basic Auth username and password. Please set it in the config.properties");
			}
		}

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

		timer.scheduleAtFixedRate(timerTask, 20 * 1000, 10 * 1000);
	}

	public boolean canProcess(Message theMessage) {
		// Override this method. If not override, we will return cannot process.
		return false;
	}

	public Message processMessage(Message theMessage, Map<String, Object> theMetadata)
			throws ReceivingApplicationException, HL7Exception {
		LOGGER.debug("Message to be processed: \n" + theMessage.toString());

		ErrorCode error = mapMyMessage(theMessage);
		if (error != ErrorCode.NOERROR) {
			// Create an exception.
			throw new HL7Exception(error.toString());
		}

		try {
			return theMessage.generateACK();
		} catch (IOException e) {
			throw new HL7Exception(e);
		} catch (Exception e) {
			throw new ReceivingApplicationException(e);
		}
	}

	protected ErrorCode mapMyMessage(Message msg) {
		// Override this to return correct return code to HL7 v2 sender.
		return null;
	}

	/**
	 * @param msg
	 * @param i
	 * @param ecr_json
	 * @param patient_json
	 * @param laborders_json
	 * @param j
	 */
//	protected ErrorCode addLabOrderFromHL7Message(Message msg, int i, JSONObject ecr_json, ECR ecr, JSONObject patient_json, JSONArray laborders_json, int j) {
//		Object orderObs;
//		if (getMyParser().getMyVersion().equalsIgnoreCase("2.3")) {
//			ORU_R01_RESPONSE patient_result = ((ca.uhn.hl7v2.model.v23.message.ORU_R01)msg).getRESPONSE(i);
//			orderObs = patient_result.getORDER_OBSERVATION(j);
//		} else
//		if (getMyParser().getMyVersion().equalsIgnoreCase("2.3.1")) {
//			ORU_R01_PIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI patient_result = ((ca.uhn.hl7v2.model.v231.message.ORU_R01)msg).getPIDPD1NK1NTEPV1PV2ORCOBRNTEOBXNTECTI(i);
//			orderObs = patient_result.getORCOBRNTEOBXNTECTI(j);
//		} else {
//			ORU_R01_PATIENT_RESULT patient_result = ((ca.uhn.hl7v2.model.v251.message.ORU_R01)msg).getPATIENT_RESULT(i);
//			orderObs = patient_result.getORDER_OBSERVATION(j);
//		}
//
//		// Parse Observation
//		JSONObject laborder_json = getMyParser().map_order_observation (orderObs);
//		if (laborder_json == null) {
//			return ErrorCode.ORDER_OBSERVATION;
//		}
//		laborders_json.put(laborder_json);
//		LabOrderCode labOrderCode = new LabOrderCode();
//		
//		
//		// We add lab results to lab order.
//		JSONArray labresults_json = new JSONArray();
//		laborder_json.put("Laboratory_Results", labresults_json);
//		
//		
//		int totalObservations;
//		if (myParser.myVersion.equalsIgnoreCase("2.3")) {
//			totalObservations = ((ca.uhn.hl7v2.model.v23.group.ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATIONReps();
//		} else
//		if (myParser.myVersion.equalsIgnoreCase("2.3.1")) {
//			totalObservations = ((ORU_R01_ORCOBRNTEOBXNTECTI)orderObs).getOBXNTEReps();
//		} else {
//			totalObservations = ((ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATIONReps();
//		}
//		
//		for (int k=0; k<totalObservations; k++) {
//			Object obsResult;
//			if (myParser.myVersion.equalsIgnoreCase("2.3")) {
//				obsResult = ((ca.uhn.hl7v2.model.v23.group.ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATION(k);
//			} else
//			if (myParser.myVersion.equalsIgnoreCase("2.3.1")) {
//				obsResult = ((ORU_R01_ORCOBRNTEOBXNTECTI)orderObs).getOBXNTE(k).getOBX();
//			} else {
//				obsResult = ((ORU_R01_ORDER_OBSERVATION)orderObs).getOBSERVATION(k).getOBX();						
//			}
//			JSONObject labresult_json = myParser.map_lab_result (obsResult);
//			if (labresult_json == null) {
//				return ErrorCode.LAB_RESULTS;
//			}
//			labresults_json.put(labresult_json);
//			
//			LabResult labResult = new LabResult();
//			labResult.setcode(labresult_json.getString("Code"));
//			labResult.setdisplay(labresult_json.getString("Display"));
//			labResult.setsystem(labresult_json.getString("System"));
//			labResult.setDate(labresult_json.getString("Date"));
//			if ( labresult_json.has("Value") ) {
//				Object labResultValue = labresult_json.get("Value");
//				labResult.setValue(labResultValue.toString());
//				if ( labresult_json.has("Unit") ) {
//					JSONObject unit = labresult_json.getJSONObject("Unit");
//					if ( unit != null ) {
//						String unit_system = unit.getString("System");
//						String unit_display = unit.getString("Display");
//						String unit_code = unit.getString("Code");
//					
//						CodeableConcept concept = new CodeableConcept(unit_system, unit_display, unit_code);
//						labResult.setUnit(concept);
//					}
//				}
//			}
//			labOrderCode.getLaboratory_Results().add(labResult);
//		}
//		
//		// For each order, we have provider, facility, order date and reason information.
//		// We put this information in the high level.
//		//
//		// Provider and Facility at Top ECR level.
//		// Order Date and Reason at Patient level.
//		//
//		// Provider and Facility: 
//		// We are in the Order Loop. So, we will come back. However, 
//		// ECR allows only one provider and facility. So, this can be overwritten
//		// by next order if provider info exists.
//		if (!laborder_json.isNull("Provider")) {
//			JSONObject provider_json = laborder_json.getJSONObject("Provider");
//			if (provider_json != null) 
//				myParser.add_provider (provider_json, ecr_json);
//		}				
//		
//		if (!laborder_json.isNull("Facility")) {
//			JSONObject facility_json = laborder_json.getJSONObject("Facility");
//			if (facility_json != null) ecr_json.put("Facility", facility_json);
//		}
//		
//		// Order Date and Reason. 
//		// We have Visit DateTime in ECR. We will put order date as a visit date
//		// as the order usually made when a patient visits a clinic.
//		if (!laborder_json.isNull("Date")) {
//			String orderDate_json = laborder_json.getString("Date");
//			if (orderDate_json != null) patient_json.put("Visit_DateTime", orderDate_json);
//		}
//		
//		// We have reasons in lab order. We put this in the trigger code.
//		if (!laborder_json.isNull("Reasons")) {
//			JSONArray reasons_json = laborder_json.getJSONArray("Reasons");
//			JSONArray triggercode_json;
//			if (patient_json.isNull("Tigger_Code")) {
//				triggercode_json = new JSONArray();
//				patient_json.put("Trigger_Code", triggercode_json);
//			} else {
//				triggercode_json = patient_json.getJSONArray("Trigger_Code");
//			}
//			if (reasons_json != null) {
//				for (int c=0; c<reasons_json.length(); c++) {
//					triggercode_json.put(reasons_json.get(c));
//				}
//			}
//		}
//		
//		return ErrorCode.NOERROR;
//	}
//	

	public int process_q() {
		String jsonString = "";
		int ret = 0;
		if (queueFile.isEmpty())
			return ret;
		boolean success = true;
		try {
			byte[] data = queueFile.peek();
			queueFile.remove();
			jsonString = new String(data, StandardCharsets.UTF_8);
			System.out.println("JSON object from queue(" + queueFile.size() + "):" + jsonString);
			JSONObject ecrJson = new JSONObject(jsonString);
			sendData(ecrJson);
		} catch (JSONException e) {
			success = false;
			// We have ill-formed JSON. Remove it from queue.
			LOGGER.error("Failed to process JSON data in Queue: " + e.getMessage() + "\nJSON data:" + jsonString);
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

	public boolean authorize(String theUriPath, String theUsername, String thePassword) {
		// Check if environment variables are set for this. If so, use them.
		String envUsername;
		String envPassword;
		
		envUsername = System.getenv("HTTP_AUTH_USER");
		envPassword = System.getenv("HTTP_AUTH_PASSWORD");
		
		if (envUsername != null && !envUsername.isEmpty()) {
			theUsername = envUsername;
		}
		
		if (envPassword != null && !envPassword.isEmpty()) {
			thePassword = envPassword;
		}
		
		LOGGER.info("Authenticating for " + theUriPath + ", " + theUsername + ", " + thePassword);

		if ("/elrreceiver".equals(theUriPath) || "/elrreceiver/".equals(theUriPath)) {
			if (httpUser.equals(theUsername) && httpPw.equals(thePassword)) {
				return true;
			}
		}
		
		return false;		
	}

//	void send_ecr(JSONObject ecrJson) 
//		throws Exception {
//
////		System.out.println("ECR Report submitted:"+ecrJson.toString());
////		return;
//		
////Todo: Deal with this later. Just add ID for now.
//		
//		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
//		Long id_num = (Long) (timestamp.getTime()/10000);
//		ecrJson.put("id", id_num.toString());
//		
//		Client client = Client.create();
//		WebResource webResource = client.resource(controller_api_url);
//		
//		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecrJson.toString());
//		if (response.getStatus() != 201 && response.getStatus() != 200) {
//			// Failed to write ECR. We should put this in the queue and retry.
//			LOGGER.error("Failed to talk to PHCR controller for ECR Resport:\n"+ecrJson.toString());
//			System.out.println("Failed to talk to PHCR controller:"+ecrJson.toString());
//			queueFile.add(ecrJson.toString().getBytes());
//			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
//		} else {
//			LOGGER.info("ECR Report submitted:"+ecrJson.toString());
//			System.out.println("ECR Report submitted:"+ecrJson.toString());
//		}
//	}
}
