package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.squareup.tape2.QueueFile;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
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

public class HL7v2ReceiverApplication<v extends BaseHL7v2Parser> implements ReceivingApplication<Message> {
	private String controller_api_url;
	private boolean useTls;
	private QueueFile queueFile = null;
	private TimerTask timerTask = null;
	private Timer timer= null;
	private v myParser = null;

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
	
	public void config(String controller_api_url, boolean useTls, String qFileName, String ecr_template_filename) 
		throws Exception {
		
		this.controller_api_url = controller_api_url;
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
		// Override this method. If not override, we will return cannot process.
		return false;
	}

	public Message processMessage(Message theMessage, Map<String, Object> theMetadata)
			throws ReceivingApplicationException, HL7Exception {
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
	
	public int process_q() {
		String jsonString = "";
		int ret = 0;
		if (queueFile.isEmpty()) return ret;
		boolean success = true;
		try {
			byte[] data = queueFile.peek();
			queueFile.remove();
			jsonString = new String (data, StandardCharsets.UTF_8);
			System.out.println("JSON object from queue("+queueFile.size()+"):"+jsonString);
			JSONObject ecrJson = new JSONObject (jsonString);
			send_ecr(ecrJson);
		} catch (JSONException e) {
			success = false;
			// We have ill-formed JSON. Remove it from queue.
			LOGGER.error("Failed to process JSON data in Queue: "+e.getMessage()+"\nJSON data:"+jsonString);
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
		WebResource webResource = client.resource(controller_api_url);
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecrJson.toString());
		if (response.getStatus() != 201 && response.getStatus() != 200) {
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
