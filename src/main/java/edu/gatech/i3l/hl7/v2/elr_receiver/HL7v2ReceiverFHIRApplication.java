package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.ws.rs.core.MediaType;

import org.apache.log4j.Logger;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.dstu3.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.dstu3.model.HumanName;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.MessageHeader;
import org.hl7.fhir.dstu3.model.MessageHeader.MessageDestinationComponent;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.ResourceType;
import org.hl7.fhir.dstu3.model.StringType;
import org.json.JSONObject;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.file.FileDataBodyPart;
import com.sun.jersey.multipart.file.StreamDataBodyPart;
import com.sun.jersey.multipart.impl.MultiPartWriter;

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
	static long expireSeconds = 0L;
	static String accessToken = null;
	static String tokenType = null;

	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v2ReceiverFHIRApplication.class.getName());

	public HL7v2ReceiverFHIRApplication() {
		ctx = FhirContext.forDstu3();
	}

	@Override
	public boolean canProcess(Message theMessage) {
		// We accepts when the follow conditions met.
		// - MSH-21 Message Profile Identifier: We need to talk to Lab (eg Labcorp) to
		// make sure
		// that we use correct profile. ELR document has the following in the example
		// LRI_Common_Component^^2.16.840.1.113883.9
		// .16^ISO~LRI_GU_Component^^2.16.840.1.113883.9.12^ISO~LAB_RU_Componen
		// t^^2.16.840.1.113883.9.14^ISO
		// - MSH-12.1: 2.5.1 or 2.3.1 We support HL7 v2.5.1 or v2.3.1
		// - MSH-9(1.2.3): ORU^R01^ORU_R01 messages (in 1.10.1)

		// Check MSH-21 for the message profile
		// TODO: Implement this after discussing with LabCorp

		// Check the version = v2.5.1 or v2.3.1
		if (theMessage.getVersion().equalsIgnoreCase("2.3") == true) {
			LOGGER.info("Message Received with v2.3. Setting a parser for FHIR STU3");
			setMyParser((v) new HL7v23FhirStu3Parser());
		} else {
			LOGGER.info("Message Received, but is not v2.3. Received message version is " + theMessage.getVersion());
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
				LOGGER.info(
						"Message with correct version received, but not ORU_R01 message type. Receved message type: "
								+ t.get("/MSH-9-1") + " " + t.get("/MSH-9-2") + " " + t.get("/MSH-9-3"));
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
		// see
		// http://hl7-definition.caristix.com:9010/Default.aspx?version=HL7+v2.3&triggerEvent=ORU_R01
		//
		// Construct Bundle with Message type.
		List<Bundle> bundles = getMyParser().executeParser(msg);
		if (bundles == null) {
			return ErrorCode.INTERNAL;
		}

		try {
			for (Bundle bundle : bundles) {
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

	private void sendFhirToOpenMdi(JSONObject fhirJson, String meOffice) {
		ClientResponse response;
		Client client;
		WebResource webResource;

		String authUrl = System.getenv("OPENMDI_AUTH_API_URL");
		String dataUrl = System.getenv("OPENMDI_DOC_API_URL");

		if (authUrl == null || authUrl.isEmpty() || dataUrl == null || dataUrl.isEmpty()) {
			return;
		}

		String openMdiClienId = System.getenv("OPENMDI_AUTH_API_CLIENT_ID");
		String openMdiClientSecret = System.getenv("OPENMDI_AUTH_API_CLIENT_SECRET");
		String meOfficeMDI = meOffice.replaceAll(" ", "_").replaceAll("/", "_");

		dataUrl = dataUrl.replace("{orgName}", meOfficeMDI);
		client = Client.create();

		// First, check if we have valid not expired token.
		long currentSeconds = Instant.now().getEpochSecond();
		if (expireSeconds == 0L || expireSeconds < currentSeconds || accessToken == null) {
			// renew access token
			byte[] auth = (openMdiClienId + ":" + openMdiClientSecret).getBytes(StandardCharsets.UTF_8);
			String encoded = Base64.getEncoder().encodeToString(auth);

			webResource = client.resource(authUrl);
			response = webResource.type(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
					.header("Cache-Control", "no-cache").header("Authorization", "Basic " + encoded)
					.post(ClientResponse.class, "grant_type=client_credentials&scope=openmdi");

			if (response.getStatus() != 200 && response.getStatus() != 201) {
				LOGGER.error("Failed to obtain token from OpenMDI for this credential");
				return;
			}

			String responseEntity = response.getEntity(String.class);
			JSONObject responseJson = new JSONObject(responseEntity);
			accessToken = responseJson.getString("access_token");
			if (accessToken == null || accessToken.isEmpty()) {
				LOGGER.error("Access token received fro OpenMDI is nll or empty");
				return;
			}

			tokenType = responseJson.getString("token_type");
			if (tokenType == null || tokenType.isEmpty()) {
				tokenType = "Bearer";
			}

			// update expireSeconds
			int expiresIn = responseJson.getInt("expires_in");
			expireSeconds = currentSeconds + expiresIn - 1;
		}

//		LinkedMultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
//		InputStream inputStream = new ByteArrayInputStream(fhirJson.toString().getBytes());
//		try {
//			map.add("file", new FileMessageResource("temp.json", inputStream));
//
//			HttpHeaders headers = new HttpHeaders();
//			headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
//			headers.setAccept(Collections.singletonList(org.springframework.http.MediaType.ALL));
//			headers.add("Authorization", tokenType + " " + accessToken);
//			
//			LOGGER.debug("Sending to "+dataUrl);
//			LOGGER.debug(headers);
//
//			HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(map, headers);
//
//			RestTemplate restTemplate = new RestTemplate();
//			ResponseEntity<String> restResponse = restTemplate.exchange(dataUrl,
//					org.springframework.http.HttpMethod.POST, requestEntity, String.class);
//			if (restResponse.getStatusCode() != HttpStatus.OK && restResponse.getStatusCode() != HttpStatus.CREATED) {
//				LOGGER.error("POSTING FHIR data to " + dataUrl + " failed");
//				LOGGER.error(restResponse.getBody());
//			} else {
//				LOGGER.debug("FHIR Data Submitted to OpenMDI\n" + restResponse.getBody());
//			}
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//			LOGGER.error(e.getMessage());
//		}

		// OpenMDI ONLY support file upload. So, save the fhir data to file
//		FileWriter file = null;
//		try {
//			file = new FileWriter("temp.json");
//			file.write(fhirJson.toString());
//			file.flush();
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

		// We should have access token now.
		
		ClientConfig cc = new DefaultClientConfig();
		cc.getClasses().add(MultiPartWriter.class);
		client = Client.create(cc);
		
		webResource = client.resource(dataUrl);
		FormDataMultiPart multipartEntity = new FormDataMultiPart();

		StreamDataBodyPart bodyPart = new StreamDataBodyPart();
		bodyPart.setName("asset");
		bodyPart.setFilename("tempStream.json");
		InputStream inputStream = new ByteArrayInputStream(fhirJson.toString().getBytes());
		bodyPart.setStreamEntity(inputStream);
//		FormDataBodyPart bodyPart = new FormDataBodyPart("asset", fhirJson.toString(),
//		FileDataBodyPart bodyPart = new FileDataBodyPart("asset", new File("temp.json"),
//				new MediaType("application", "fhir+json"));

		bodyPart.setMediaType(new MediaType("application", "fhir+json"));
		multipartEntity.bodyPart(bodyPart);
//		ContentDisposition cd = bodyPart.getContentDisposition();

//		byte[] data = fhirJson.toString().getBytes();
//		multipartEntity.bodyPart(new FormDataBodyPart("asset", data, MediaType.APPLICATION_OCTET_STREAM_TYPE));

		response = webResource.type(MediaType.MULTIPART_FORM_DATA_TYPE).accept(MediaType.APPLICATION_JSON)
				.header("Authorization", tokenType + " " + accessToken).post(ClientResponse.class, multipartEntity);

		if (response.getStatus() != 200 && response.getStatus() != 201) {
			LOGGER.error("POSTING FHIR data to " + webResource.toString() + " failed");
			LOGGER.error(response.getStatusInfo());
		} else {
			LOGGER.debug("FHIR Data Submitted to OpenMDI\n" + response.getEntity(String.class));
		}
		
//		if (file != null) {
//			try {
//				file.flush();
//				file.close();
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		}
	}

	private void sendFhir(JSONObject fhirJsonObject) throws Exception {

		Client client = Client.create();
		WebResource webResource = client.resource(getControllerApiUrl());
		IParser p = FhirContext.forDstu3().newJsonParser();

		String meOffice;
		if (getMyParser() == null || getMyParser().getReceivingFacilityName() == null) {
			// Get meOffice from destination in FHIR.
			Bundle bundleMessage = p.parseResource(Bundle.class, fhirJsonObject.toString());
			List<BundleEntryComponent> messageEntryList = bundleMessage.getEntry();
			BundleEntryComponent messageHeaderEntry = messageEntryList.get(0);
			Resource messageHeader = messageHeaderEntry.getResource();
			if (messageHeader.getResourceType() != ResourceType.MessageHeader) {
				// This is a bug... we should have a header.
				LOGGER.error("Message Bundler withoug MessageHeader. Message ignored");
				return;
			}
			List<MessageDestinationComponent> destinations = ((MessageHeader) messageHeader).getDestination();
			if (destinations.size() > 0) {
				meOffice = destinations.get(0).getName();
				if (meOffice == null || meOffice.isEmpty()) {
					meOffice = destinations.get(0).getEndpoint();
				}
			} else {
				meOffice = "NOT_SPECIFIED";
			}
		} else {
			meOffice = getMyParser().getReceivingFacilityName();
		}

		ClientResponse response = webResource.type("application/fhir+json").post(ClientResponse.class,
				fhirJsonObject.toString());
		LOGGER.info("FHIR Message submitted:" + fhirJsonObject.toString());

		if (response.getStatus() != 201 && response.getStatus() != 200) {
			// Failed to write ECR. We should put this in the queue and retry.
			LOGGER.error("Failed to talk to FHIR Controller for Message:\n" + fhirJsonObject.toString());
			System.out.println("Failed to talk to FHIR controller:" + fhirJsonObject.toString());
			getQueueFile().add(fhirJsonObject.toString().getBytes());
			throw new RuntimeException("Failed: HTTP error code : " + response.getStatus());
		} else {
			String indexServiceApiUrl = getIndexServiceApiUrl();
			String indexServiceApiUrlEnv = System.getenv("PATIENT_INDEX_SERVER");
			if (indexServiceApiUrlEnv == null)
				return;
			else
				indexServiceApiUrl = indexServiceApiUrlEnv;

			String resp = response.getEntity(String.class);
			Bundle bundleResponse = p.parseResource(Bundle.class, resp);
			if (bundleResponse == null) {
				throw new RuntimeException("Failed: FHIR response error : " + resp);
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
								String id = path[path.length - 1];

								Bundle messageFhir = p.parseResource(Bundle.class, fhirJsonObject.toString());
								List<BundleEntryComponent> messageEntryList = messageFhir.getEntry();
								boolean done = false;

								for (BundleEntryComponent messageEntry : messageEntryList) {
									Resource resource = messageEntry.getResource();
									if (resource != null && !resource.isEmpty()) {
										if (resource.getResourceType() == ResourceType.Patient) {
											Identifier identifier = ((Patient) resource).getIdentifierFirstRep();
											String caseNumber = null;
											if (identifier != null && !identifier.isEmpty()) {
												caseNumber = identifier.getValue();
												System.out.println("Patient ID = " + id + ", caseNumber:" + caseNumber);
												if (caseNumber != null && !caseNumber.isEmpty()) {
													StringType firstName = new StringType();
													String lastName = new String();
													HumanName name = ((Patient) resource).getNameFirstRep();
													if (name != null && !name.isEmpty()) {
														List<StringType> given = name.getGiven();
														if (!given.isEmpty()) {
															firstName = given.get(0);
														}
														lastName = name.getFamily();
													}
													AdministrativeGender genderFhir = ((Patient) resource).getGender();
													String gender = "";
													if (genderFhir != null) {
														gender = genderFhir.toString();
													}

													// construct index info.
													String indexInfo = "{\n" + "  \"firstName\": \""
															+ firstName.getValue() + "\",\n" + "  \"gender\": \""
															+ gender + "\",\n" + "  \"lastName\": \"" + lastName
															+ "\",\n" + "  \"listOfFhirSources\": [\n" + "    {\n"
															+ "      \"fhirPatientId\": \"" + id + "\",\n"
															+ "      \"fhirServerUrl\": \""
															+ getControllerApiUrl().replace("/$process-message", "")
															+ "\",\n" + "      \"fhirVersion\": \"STU3\",\n"
															+ "      \"type\": \"LAB\"\n" + "    }\n" + "  ],\n"
															+ "  \"meCaseNumber\": \"" + caseNumber + "\",\n"
															+ "  \"meOffice\": \"" + meOffice + "\"\n" + "}";
													LOGGER.info("Index register:" + indexInfo);
													webResource = client.resource(indexServiceApiUrl + "/manage");
													String user = System.getenv("INDEX_SERVER_USER");
													if (user == null)
														user = "decedent";
													String pw = System.getenv("INDEX_SERVER_PASSWORD");
													if (pw == null)
														pw = "password";
													byte[] auth = (user + ":" + pw).getBytes(StandardCharsets.UTF_8);
													String encoded = Base64.getEncoder().encodeToString(auth);
													response = webResource.type("application/json")
															.header("Authorization", "Basic " + encoded)
															.post(ClientResponse.class, indexInfo);
													if (response.getStatus() != 200 && response.getStatus() != 201) {
														LOGGER.error("Index Service Registration Failed for "
																+ indexServiceApiUrl + "/manage with "
																+ response.getStatus());
													} else {
														LOGGER.info("Decedent Index submitted to: " + indexServiceApiUrl
																+ "/manage");
													}
												}
												done = true;
												break;
											}
										}
									}
								}
								if (done)
									break;
							}
						}
					}
				}
			}

			// FHIR data submitted to our internal FHIR server.
			// Now, we submit this to OpenMDI if requred.
			sendFhirToOpenMdi(fhirJsonObject, meOffice);
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
