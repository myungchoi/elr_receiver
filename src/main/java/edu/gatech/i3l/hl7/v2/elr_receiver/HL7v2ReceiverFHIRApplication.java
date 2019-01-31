package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.apache.log4j.Logger;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.dstu3.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.dstu3.model.HumanName;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.ResourceType;
import org.hl7.fhir.dstu3.model.StringType;
import org.json.JSONObject;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import edu.gatech.i3l.hl7.v2.parser.fhir.BaseHL7v2FHIRParser;
import edu.gatech.i3l.hl7.v2.parser.fhir.HL7v23FhirStu3Parser;

/*
 * HL7v2 Message Receiver Application for ELR
 * 
 * Author : Myung Choi (myung.choi@gtri.gatech.edu)
 * Version: 0.1-beta
 * 
 * Implementation Guide: V251_IG_LB_LABRPTPH_R2_DSTU_R1.1_2014MAY.PDF (Available from HL7.org)
 */

public class HL7v2ReceiverFHIRApplication<v extends BaseHL7v2FHIRParser> extends HL7v2ReceiverApplication<v> {
	private FhirContext ctx = null;

	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v2ReceiverFHIRApplication.class.getName());
		
	public HL7v2ReceiverFHIRApplication() {
		ctx = FhirContext.forDstu3();
	}
	
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
		if (theMessage.getVersion().equalsIgnoreCase("2.3") == true) {
			LOGGER.info("Message Received with v2.3. Setting a parser for FHIR STU3");
			setMyParser((v) new HL7v23FhirStu3Parser());
		} else {
			LOGGER.info("Message Received, but is not v2.3. Received message version is "+theMessage.getVersion());
			return false;
		}
		
		// Check the message type
		Terser t = new Terser(theMessage);
		try {
			String MSH91 = t.get("/MSH-9-1");
			String MSH92 = t.get("/MSH-9-2");
			String MSH93 = t.get("/MSH-9-3");
			
			if ((MSH91 != null && MSH91.equalsIgnoreCase("ORU") == false) 
					|| (MSH92 != null && MSH92.equalsIgnoreCase("R01") == false)
					|| (MSH93 != null && MSH93.equalsIgnoreCase("ORU_R01") == false)) {
				LOGGER.info("Message with correct version received, but not ORU_R01 message type. Receved message type: "+t.get("/MSH-9-1")+" "+t.get("/MSH-9-2")+" "+t.get("/MSH-9-3"));
				return false;
			}
		} catch (HL7Exception e) {
			e.printStackTrace();
			return false;
		}
		
		return true;
	}
	
	@Override
	protected ErrorCode mapMyMessage(Message msg) {
		// Mapping ELR message to FHIR.
		//
		// Investigate Response Group
		// see http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7+v2.3&triggerEvent=ORU_R01
		//
		// Construct Bundle with Message type.
		List<Bundle> bundles = getMyParser().executeParser(msg);
		if (bundles == null) {
			return ErrorCode.INTERNAL;
		}
				
		try {
			for (Bundle bundle: bundles) {
				String fhirJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
				JSONObject fhirJsonObject = new JSONObject(fhirJson);
				sendFhir(fhirJsonObject);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return ErrorCode.INTERNAL;
		}
		
		return ErrorCode.NOERROR;
	}
	
	private void sendFhir(JSONObject fhirJsonObject) 
		throws Exception {

		Client client = Client.create();
		WebResource webResource = client.resource(getControllerApiUrl());
		
		ClientResponse response = webResource.type("application/json").post(ClientResponse.class, fhirJsonObject.toString());
		LOGGER.info("FHIR Message submitted:"+fhirJsonObject.toString());

		if (response.getStatus() != 201 && response.getStatus() != 200) {
			// Failed to write ECR. We should put this in the queue and retry.
			LOGGER.error("Failed to talk to FHIR Controller for Message:\n"+fhirJsonObject.toString());
			System.out.println("Failed to talk to FHIR controller:"+fhirJsonObject.toString());
			getQueueFile().add(fhirJsonObject.toString().getBytes());
			throw new RuntimeException("Failed: HTTP error code : "+response.getStatus());
		} else {
			String indexServiceApiUrl = getIndexServiceApiUrl();
			String indexServiceApiUrlEnv = System.getenv("PATIENT_INDEX_SERVER");
			if (indexServiceApiUrlEnv == null) return;
			else indexServiceApiUrl = indexServiceApiUrlEnv;
			
			String resp = response.getEntity(String.class);
			IParser p = FhirContext.forDstu3().newJsonParser();
			Bundle bundleResponse = p.parseResource(Bundle.class, resp);
			if (bundleResponse == null) {
				throw new RuntimeException("Failed: FHIR response error : "+resp);
			} else {
				List<BundleEntryComponent> entryList = bundleResponse.getEntry();
				for (BundleEntryComponent entry : entryList) {
					// We just check Patient id from location in response.
					BundleEntryResponseComponent entryResponse = entry.getResponse();
					if (entryResponse != null && !entryResponse.isEmpty()) {
						String location = entryResponse.getLocation();
						if (location != null && !location.isEmpty()) {
							if (location.contains("Patient")) {
								String[] path = location.split("/");
								String id = path[path.length-1];
								
								Bundle messageFhir = p.parseResource(Bundle.class, fhirJsonObject.toString());
								List<BundleEntryComponent> messageEntryList = messageFhir.getEntry();
								boolean done = false;
								for (BundleEntryComponent messageEntry : messageEntryList) {
									Resource resource = messageEntry.getResource();
									if (resource != null && !resource.isEmpty()) {
										if (resource.getResourceType() == ResourceType.Patient) {
											Identifier identifier = ((Patient)resource).getIdentifierFirstRep();
											String caseNumber = null;
											if (identifier != null && !identifier.isEmpty()) {
												caseNumber = identifier.getValue();
												System.out.println("Patient ID = "+id+", caseNumber:"+caseNumber);
												if (caseNumber != null && !caseNumber.isEmpty()) {
													StringType firstName = new StringType();
													String lastName = new String();
													HumanName name = ((Patient)resource).getNameFirstRep();
													if (name != null && !name.isEmpty()) {
														List<StringType> given = name.getGiven();
														if (!given.isEmpty()) {
															firstName = given.get(0);
														}
														lastName = name.getFamily();
													}
													AdministrativeGender genderFhir = ((Patient)resource).getGender();
													String gender = "";
													if (genderFhir != null) {
														gender = genderFhir.toString();
													}
													// construct index info.
													String indexInfo = "{\n" + 
															"  \"firstName\": \""+firstName.getValue()+"\",\n" + 
															"  \"gender\": \""+gender+"\",\n" + 
															"  \"lastName\": \""+lastName+"\",\n" + 
															"  \"listOfFhirSources\": [\n" + 
															"    {\n" + 
															"      \"fhirPatientId\": \""+id+"\",\n" + 
															"      \"fhirServerUrl\": \""+getControllerApiUrl().replace("/$process-message", "")+"\",\n" + 
															"      \"fhirVersion\": \"STU3\",\n" + 
															"      \"type\": \"LAB\"\n" + 
															"    }\n" + 
															"  ],\n" + 
															"  \"meCaseNumber\": \""+caseNumber+"\",\n" + 
															"  \"meOffice\": \""+getMyParser().getReceivingFacilityName()+"\"\n" + 
															"}";
													LOGGER.info("Index register:"+indexInfo);
													webResource = client.resource(indexServiceApiUrl+"/manage");
													String user = System.getenv("INDEX_SERVER_USER");
													if (user == null) user = "decedent";
													String pw = System.getenv("INDEX_SERVER_PASSWORD");
													if (pw == null) pw = "password";
													byte[] auth = (user+":"+pw).getBytes(StandardCharsets.UTF_8);
													String encoded = Base64.getEncoder().encodeToString(auth);
													response = webResource.type("application/json").header("Authorization", "Basic "+encoded).post(ClientResponse.class, indexInfo);
													if (response.getStatus() != 200 && response.getStatus() != 201) {
														LOGGER.error("Index Service Registration Failed for "+indexServiceApiUrl+"/manage with "+response.getStatus());
													}
													LOGGER.info("Decedent Index submitted to: "+indexServiceApiUrl+"/manage");
												}
												done = true;
												break;
											}
										}
									}
								}
								if (done) break;
							}
						}
					}
				}
			}
		}
	}

	public void sendData(JSONObject jsonData) {
		try {
			sendFhir(jsonData);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
