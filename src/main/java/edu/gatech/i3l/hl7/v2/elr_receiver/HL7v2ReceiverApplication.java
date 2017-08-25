package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.squareup.tape2.QueueFile;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.HD;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
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
	
	public void config(String phcr_controller_api_url, boolean useTls, String qFileName) 
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
		
		String ecr_json = map2ecr((ORU_R01) theMessage);
		
		try {
			send_ecr(ecr_json);
			return theMessage.generateACK();
		} catch (IOException e) {
			throw new HL7Exception(e);
		} catch (Exception e) {
			throw new ReceivingApplicationException(e);
		}
	}

	private String map2ecr(ORU_R01 msg) {
		// Mapping ELR message to ECR.
		//
		// Investigate Patient Result Group
		// see http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7%20v2.5.1&triggerEvent=ORU_R01
		//
		String selectedPatientID = "";
		String patientID = "";
		int totalRepPatientResult = msg.getPATIENT_RESULTReps();
		for (int i=0; i<totalRepPatientResult; i++) {
			ORU_R01_PATIENT patient = msg.getPATIENT_RESULT(i).getPATIENT();
			int totPID3 = patient.getPID().getPid3_PatientIdentifierListReps();
			for (int j=0; j<totPID3; j++) {
				CX pIdentifier = patient.getPID().getPid3_PatientIdentifierList(j);
				
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
			
			if (selectedPatientID.isEmpty() == false)
				break;
		}
		
		if (selectedPatientID.isEmpty()) {
			if (patientID.isEmpty()) {
				// We have no patient information. Return with null.
				return null;
			}
			selectedPatientID = patientID;
		}
		
		// Get provider information from ELR.
		
		
		return null;
	}
	
	public int process_q() {
		int ret = 0;
		if (queueFile.isEmpty()) return ret;
		boolean success = true;
		try {
			byte[] data = queueFile.peek();
			String ecr = data.toString();
			send_ecr(ecr);
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
	
	private void send_ecr(String ecr_json) 
		throws Exception {
		
		Client client = Client.create();
		WebResource webResource = client.resource(phcr_controller_api_url);
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, ecr_json);
		if (response.getStatus() != 201) {
			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
		} else {
			// Failed to write ECR. We should put this in the queue and retry.
			queueFile.add(ecr_json.getBytes());
		}		
	}
}
