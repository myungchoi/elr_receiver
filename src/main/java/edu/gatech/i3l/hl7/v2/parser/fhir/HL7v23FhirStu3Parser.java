package edu.gatech.i3l.hl7.v2.parser.fhir;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.log4j.Logger;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.DateTimeType;
import org.hl7.fhir.dstu3.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.dstu3.model.Enumerations.MessageEvent;
import org.hl7.fhir.dstu3.model.HumanName;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.MessageHeader;
import org.hl7.fhir.dstu3.model.MessageHeader.MessageDestinationComponent;
import org.hl7.fhir.dstu3.model.MessageHeader.MessageSourceComponent;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Observation.ObservationReferenceRangeComponent;
import org.hl7.fhir.dstu3.model.Observation.ObservationStatus;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Quantity;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.SimpleQuantity;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.dstu3.model.Bundle.HTTPVerb;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.v23.datatype.CE;
import ca.uhn.hl7v2.model.v23.datatype.CM_MSG;
import ca.uhn.hl7v2.model.v23.datatype.CX;
import ca.uhn.hl7v2.model.v23.datatype.EI;
import ca.uhn.hl7v2.model.v23.datatype.FT;
import ca.uhn.hl7v2.model.v23.datatype.HD;
import ca.uhn.hl7v2.model.v23.datatype.ID;
import ca.uhn.hl7v2.model.v23.datatype.IS;
import ca.uhn.hl7v2.model.v23.datatype.NM;
import ca.uhn.hl7v2.model.v23.datatype.SI;
import ca.uhn.hl7v2.model.v23.datatype.ST;
import ca.uhn.hl7v2.model.v23.datatype.TS;
import ca.uhn.hl7v2.model.v23.datatype.TSComponentOne;
import ca.uhn.hl7v2.model.v23.datatype.TX;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_RESPONSE;
import ca.uhn.hl7v2.model.v23.segment.MSH;
import ca.uhn.hl7v2.model.v23.segment.NTE;
import ca.uhn.hl7v2.model.v23.segment.OBR;
import ca.uhn.hl7v2.model.v23.segment.OBX;
import ca.uhn.hl7v2.model.v23.segment.ORC;

public class HL7v23FhirStu3Parser extends BaseHL7v2FHIRParser {
	MessageHeader messageHeader = null;
	String sendingFacilityName = null;
	String receivingFacilityName = null;

	// Logger setup
	final static Logger LOGGER = Logger.getLogger(HL7v23FhirStu3Parser.class.getName());

	private void initialize(Message msg) {
		mapMessageHeader((ca.uhn.hl7v2.model.v23.message.ORU_R01) msg);
	}

	public HL7v23FhirStu3Parser() {
		setMyVersion("2.3");
	}

	public String getSendingFacilityName() {
		return this.sendingFacilityName;
	}

	public void setSendingFacilityName(String sendingFacilityName) {
		this.sendingFacilityName = sendingFacilityName;
	}
	
	public String getReceivingFacilityName() {
		return this.receivingFacilityName;
	}
	
	public void setReceivingFacilityName(String receivingFacilityName) {
		this.receivingFacilityName = receivingFacilityName;
	}

	public List<Bundle> executeParser(Message msg) {
		ca.uhn.hl7v2.model.v23.message.ORU_R01 oruR01Message = (ca.uhn.hl7v2.model.v23.message.ORU_R01) msg;
		List<Bundle> bundles = new ArrayList<Bundle>();
		Patient subject = null;

		// First clear up the list.
		initialize(msg);

		int numberOfResponses = oruR01Message.getRESPONSEReps();
		for (int i = 0; i < numberOfResponses; i++) {
			Bundle bundle = new Bundle();
			bundle.setType(Bundle.BundleType.MESSAGE);

			BundleEntryComponent bundleEntryMessageHeader = new BundleEntryComponent();
			bundleEntryMessageHeader.setResource(messageHeader);
			bundle.addEntry(bundleEntryMessageHeader);

			List<Patient> returnedPatients = mapPatients(oruR01Message.getRESPONSE(i));
			// v2.3 says that there should be 1 patient. This library reads as many.
			// We just use first one if the returnedPatients is not empty.
			String patientReference = null;
			if (returnedPatients.size() > 0) {
				BundleEntryComponent bundleEntryPatient = new BundleEntryComponent();
				subject = returnedPatients.get(0);
				bundleEntryPatient.setResource(subject);
				// TODO: revisit this if we found a case where we can put request in bundle.
				// for now, FHIR spec does not indicate message to have request or response.
//				BundleEntryRequestComponent bundleEntryRequest = new BundleEntryRequestComponent();
//				bundleEntryRequest.setMethod(HTTPVerb.POST);
//				bundleEntryRequest.setUrl("Patient");
//				bundleEntryPatient.setRequest(bundleEntryRequest);
				UUID uuid = UUID.randomUUID();
				patientReference = "urn:uuid:" + uuid.toString();
				bundleEntryPatient.setFullUrl(patientReference);
				bundle.addEntry(bundleEntryPatient);
			} else {
				// We must have a patient.
				return null;
			}

			// Add Observation.
			List<Observation> returnedObservations = mapObservations(oruR01Message.getRESPONSE(i), patientReference);
			for (Observation observation : returnedObservations) {
				BundleEntryComponent bundleEntryObservation = new BundleEntryComponent();
				bundleEntryObservation.setResource(observation);
//				BundleEntryRequestComponent bundleEntryRequest = new BundleEntryRequestComponent();
//				bundleEntryRequest.setMethod(HTTPVerb.POST);
//				bundleEntryRequest.setUrl("Observation");
//				bundleEntryObservation.setRequest(bundleEntryRequest);
				UUID uuid = UUID.randomUUID();
				bundleEntryObservation.setFullUrl("urn:uuid:" + uuid.toString());
				bundle.addEntry(bundleEntryObservation);
			}

			bundles.add(bundle);
		}

		return bundles;
	}

	private List<Patient> mapPatients(ORU_R01_RESPONSE response) {
		List<Patient> retVal = new ArrayList<Patient>();

		Patient patient = new Patient();
		try {
			ORU_R01_PATIENT patientHL7 = response.getPATIENT();
			ca.uhn.hl7v2.model.v23.segment.PID pid = patientHL7.getPID();

			patient.setId(UUID.randomUUID().toString());
			
			// PID-2 to patient ID.
			// This is the accession number of NMS' client.
			CX pid2 = pid.getPid2_PatientIDExternalID();
			if (pid2 != null && !pid2.isEmpty()) {
				ST id = pid2.getCx1_ID();
				if (id != null && !id.isEmpty()) {
//					patient.setId(id.getValue());
					Identifier identifier = new Identifier();
					identifier.setSystem("External_Patient_ID");
					identifier.setValue(id.getValue());
					patient.addIdentifier(identifier);
				} else {
					// PID2 is required for NMS outbound message.
					return null;
				}
			}

			// PID-3 to patient.identifier.
			// This is NMS Workorder number assigned by NMS
			int numberOfPid3 = pid.getPid3_PatientIDInternalIDReps();
			for (int j = 0; j < numberOfPid3; j++) {
				ca.uhn.hl7v2.model.v23.datatype.CX pid3 = pid.getPid3_PatientIDInternalID(j);
				ca.uhn.hl7v2.model.v23.datatype.ST id = pid3.getCx1_ID();
				Identifier identifier = new Identifier();
				if (id != null && !id.isEmpty()) {
					identifier.setValue(id.getValue());
				}

				ca.uhn.hl7v2.model.v23.datatype.IS identifierTypeCode = pid3.getCx5_IdentifierTypeCode();
				if (identifierTypeCode != null && !identifierTypeCode.isEmpty()) {
					CodeableConcept codeableConcept = new CodeableConcept();
					Coding coding = new Coding();
					coding.setSystem("http://hl7.org/fhir/v2/0203");
					coding.setCode(identifierTypeCode.getValue());
					codeableConcept.addCoding(coding);
					identifier.setType(codeableConcept);
				}

				if (!identifier.isEmpty()) {
					patient.addIdentifier(identifier);
				}
			}

			// PID-5, PID-9 to patient.name
			int numberOfPid5 = pid.getPid5_PatientNameReps();
			for (int k = 0; k < numberOfPid5; k++) {
				ca.uhn.hl7v2.model.v23.datatype.XPN pid5 = pid.getPid5_PatientName(k);
				if (pid5 != null && !pid5.isEmpty()) {
					HumanName humanName = new HumanName();
					ca.uhn.hl7v2.model.v23.datatype.ST familyName = pid5.getFamilyName();
					if (familyName != null && !familyName.isEmpty()) {
						humanName.setFamily(familyName.getValue());
					}
					ca.uhn.hl7v2.model.v23.datatype.ST givenName = pid5.getGivenName();
					if (givenName != null && !givenName.isEmpty()) {
						humanName.addGiven(givenName.getValue());
					}

					patient.addName(humanName);
					break;
				}
			}

			// PID-7 to patient.birthDate
			TS pid7 = pid.getPid7_DateOfBirth();
			if (pid7 != null && !pid7.isEmpty()) {
				Date dob = pid7.getTs1_TimeOfAnEvent().getValueAsDate();
				patient.setBirthDate(dob);
			}

			// PID-8 to patient.gender
			ca.uhn.hl7v2.model.v23.datatype.IS pid8 = pid.getPid8_Sex();
			if (pid8 != null && !pid8.isEmpty()) {
				AdministrativeGender adminstrativeGender;
				if (pid8.getValue().equals("F")) {
					adminstrativeGender = AdministrativeGender.FEMALE;
				} else if (pid8.getValue().equals("M")) {
					adminstrativeGender = AdministrativeGender.MALE;
				} else if (pid8.getValue().equals("O")) {
					adminstrativeGender = AdministrativeGender.OTHER;
				} else {
					adminstrativeGender = AdministrativeGender.UNKNOWN;
				}
				patient.setGender(adminstrativeGender);
			}
		} catch (HL7Exception e) {
			e.printStackTrace();
		}

		retVal.add(patient);
		return retVal;
	}

	public List<Observation> mapObservations(ORU_R01_RESPONSE response, String subjectReference) {
		// Mapping Document:
		// https://confluence.icl.gtri.org/pages/viewpage.action?pageId=22678246#NMStoFHIR(HL7v2.3toFHIR)-ForensicFormatHL7v2.3Specification

		List<Observation> retVal = new ArrayList<Observation>();

		try {
			int totalNumberOfOrderObservation = response.getORDER_OBSERVATIONReps();
			for (int i = 0; i < totalNumberOfOrderObservation; i++) {
				ORU_R01_ORDER_OBSERVATION orderObservation = response.getORDER_OBSERVATION(i);
				OBR obr = orderObservation.getOBR();

				int totalNumberOfObservation = orderObservation.getOBSERVATIONReps();
				for (int j = 0; j < totalNumberOfObservation; j++) {
					Observation observation = new Observation();

					ORU_R01_OBSERVATION hl7Observation = orderObservation.getOBSERVATION(j);

					// Set the subject from the patient ID.
//				IdType IdType = new IdType("Patient", subject.getId());
					Reference reference = new Reference(subjectReference);
					observation.setSubject(reference);

					OBX obx = hl7Observation.getOBX();

					// See if we have a unit (from OBX-6)
					// Set the unit if available from OBX-6
					CE unit = obx.getObx6_Units();
					String unitString = new String();
					String unitCodeString = new String();
					String unitSystemString = new String();
					if (!unit.isEmpty()) {
						ID id = unit.getCe1_Identifier();
						if (id != null && !id.isEmpty()) {
							unitString = id.getValue().replace("mcg", "ug").replace(" Creat" , "{creat}");
							unitCodeString = id.getValue().replace("mcg", "ug").replace(" Creat" , "{creat}");
						}

						ST system = unit.getCe3_NameOfCodingSystem();
						if (system != null && !system.isEmpty()) {
							unitSystemString = system.getValue();
						} 
					}

					// Value Type for observation.value[x] and observation.referenceRange
					ID valueType = obx.getObx2_ValueType();
					Varies observationValue = obx.getObx5_ObservationValue(0);
					if (valueType.getValue().equals("NM")) {
						// This should be valueQuantity
						// obx5 is the ST value in NMS message
						Quantity quantity = new Quantity();
						NM numericValue = (NM) observationValue.getData();
						quantity.setValue(Double.parseDouble(numericValue.getValue()));

						// Set the unit if available from OBX-6
						if (!unitString.isEmpty()) {
							quantity.setUnit(unitString);
							quantity.setCode(unitCodeString);
						}
						if (!unitSystemString.isEmpty()) {
							quantity.setSystem(unitSystemString);
						}

						observation.setValue(quantity);
					} else { // ST and TX - we treat both ST and TX as String
						StringType valueString = null;
						if (valueType.getValue().equals("ST")) {
							ST stringValue = (ST) observationValue.getData();
							valueString = new StringType(stringValue.getValue());
						} else {
							TX textValue = (TX) observationValue.getData();
							valueString = new StringType(textValue.getValue());
						}
						observation.setValue(valueString);
					}

					// observation.code from OBX-3
					CE obx3 = obx.getObx3_ObservationIdentifier();
					CodeableConcept codeableConcept = getCodeableConceptFromCE(obx3);
					observation.setCode(codeableConcept);

					// observation.referenceRange from OBX-7
					ST obx7 = obx.getObx7_ReferencesRange();
					if (!obx7.isEmpty()) {
						String obx7StringValue = obx7.getValue();
						ObservationReferenceRangeComponent referenceRangeComponent = new ObservationReferenceRangeComponent();

						try {
							double obx7Value = Double.parseDouble(obx7StringValue);
							SimpleQuantity simpleQuantity = new SimpleQuantity();
							simpleQuantity.setValue(obx7Value);
							if (!unitString.isEmpty()) {
								simpleQuantity.setUnit(unitString);
								simpleQuantity.setCode(unitCodeString);
							}
							if (!unitSystemString.isEmpty()) {
								simpleQuantity.setSystem(unitSystemString);
							}
							referenceRangeComponent.setHigh(simpleQuantity);
						} catch (NumberFormatException nfe) {
							referenceRangeComponent.setText(obx7StringValue);
						}

						observation.addReferenceRange(referenceRangeComponent);
					}
					// observation.status from OBX-11
					ID obx11 = obx.getObx11_ObservResultStatus();
					if (obx11 != null && !obx11.isEmpty()) {
						String hl7Status = obx11.getValue();
						if ("F".equals(hl7Status)) {
							// Final Result.
							observation.setStatus(ObservationStatus.FINAL);
						} else if ("C".equals(hl7Status)) {
							// Record coming over is a correction and thus replaces a final result.
							observation.setStatus(ObservationStatus.AMENDED);
						} else if ("X".equals(hl7Status)) {
							// Record coming over is a correction and thus replaces a final result.
							observation.setStatus(ObservationStatus.CANCELLED);
						} else if ("P".equals(hl7Status)) {
							// Record coming over is a correction and thus replaces a final result.
							observation.setStatus(ObservationStatus.PRELIMINARY);
						} else {
							LOGGER.fatal(
									"OBX received with status = " + hl7Status + ". (Table:" + obx11.getTable() + ")");
							continue;
						}
					}

					// effective[x] from obx14
					TS observationDateTime = obx.getObx14_DateTimeOfTheObservation();
					DateTimeType dateTimeType = new DateTimeType(
							observationDateTime.getTs1_TimeOfAnEvent().getValueAsDate());
					observation.setEffective(dateTimeType);

					int totalNumberOfNTE = hl7Observation.getNTEReps();
					String comments = null;
					for (int k = 0; k < totalNumberOfNTE; k++) {
						// This is a comment. Observation can contain one comment.
						// So we combine all the NTEs.
						NTE nte = hl7Observation.getNTE(k);
						if (nte != null && !nte.isEmpty()) {
							int totalNumberOfNTEComment = nte.getNte3_CommentReps();
							for (int n = 0; n < totalNumberOfNTEComment; n++) {
								FT nte3 = nte.getNte3_Comment(n);
								if (nte3 != null && !nte3.isEmpty()) {
									if (comments == null) {
										comments = nte3.getValue();
									} else {
										comments = comments.concat(". " + nte3.getValue());
									}
								}
							}
						}
					}
					if (comments != null) {
						observation.setComment(comments);
					}

					retVal.add(observation);
				}
			}

		} catch (HL7Exception e) {
			e.printStackTrace();
			return null;
		}

		return retVal;
	}

	private String getValueOfHD(HD hd) {
		IS hd1 = hd.getHd1_NamespaceID();
		ST hd2 = hd.getHd2_UniversalID();
		ID hd3 = hd.getHd3_UniversalIDType();

		try {
			if (hd2 != null && !hd2.isEmpty()) {
				return hd2.getValue();
			} else if (hd1 != null && !hd1.isEmpty()) {
				return hd1.getValue();
			} else {
				return hd3.getValue();
			}
		} catch (HL7Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	private CodeableConcept getCodeableConceptFromCE(CE codeElement) {
		CodeableConcept retVal = new CodeableConcept();

		Coding coding = new Coding();
		try {
			ID id = codeElement.getCe1_Identifier();
			if (id != null && !id.isEmpty()) {
				coding.setCode(id.getValue());
			}
			ST display = codeElement.getCe2_Text();
			if (display != null && !display.isEmpty()) {
				coding.setDisplay(display.getValue());
			}
			ST system = codeElement.getCe3_NameOfCodingSystem();
			if (system != null && !system.isEmpty()) {
				coding.setSystem(system.getValue());
			} else {
				// Put facility name for the system if available.
				// For now, we put NMS Labs if sending facility is NMS.
				if (getSendingFacilityName().equalsIgnoreCase("NMS"))
					coding.setSystem("NMS Labs");
			}
			if (!coding.isEmpty()) {
				retVal.addCoding(coding);
			}
		} catch (HL7Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return retVal;
	}

	public MessageHeader mapMessageHeader(ca.uhn.hl7v2.model.v23.message.ORU_R01 oruR01message) {
		MSH msh = oruR01message.getMSH();
		if (messageHeader == null)
			messageHeader = new MessageHeader();

		HD sendingFacility = msh.getSendingFacility();
		HD receivingFacility = msh.getReceivingFacility();
		try {
			if (!sendingFacility.isEmpty()) {
				IS sendingFacilityName = sendingFacility.getHd1_NamespaceID();
				if (!sendingFacilityName.isEmpty()) {
					setSendingFacilityName(sendingFacilityName.getValue());
				}
			}

			if (!receivingFacility.isEmpty()) {
				ST receivingFacilityST = receivingFacility.getHd2_UniversalID();
				if (!receivingFacilityST.isEmpty()) {
					setReceivingFacilityName(receivingFacilityST.getValue());
				}
			}
			
			// messageheader.event from MSH9.2. MSH9.2 is triggering event.
			// For ELR, it's R01. We need to map this to FHIR message-event, which
			// can be observation-provide.
			Coding eventCoding = new Coding();
			CM_MSG msh9 = msh.getMsh9_MessageType();
			if (msh9 != null && !msh9.isEmpty()) {
				ID msh9_2 = msh9.getCm_msg2_TriggerEvent();
				if (msh9_2 != null && !msh9_2.isEmpty()) {
					if (msh9_2.getValue().equals("R01")) {
						MessageEvent messageEvent = MessageEvent.valueOf("OBSERVATIONPROVIDE");
						eventCoding.setSystem(messageEvent.getSystem());
						eventCoding.setCode(messageEvent.toCode());
						eventCoding.setDisplay(messageEvent.getDisplay());
					} else {
						eventCoding.setCode(msh9_2.getValue());
					}
				}
			}

			if (eventCoding.isEmpty()) {
				// We couldn't get any info from triggering event. Just set this to our default
				// R01 event mapping.
				MessageEvent messageEvent = MessageEvent.valueOf("OBSERVATIONPROVIDE");
				eventCoding.setSystem(messageEvent.getSystem());
				eventCoding.setCode(messageEvent.toCode());
				eventCoding.setDisplay(messageEvent.getDisplay());
			}

			messageHeader.setEvent(eventCoding);

			// messageevent.destination.name from MSH5 and MSH6
			MessageDestinationComponent messageDestination = new MessageDestinationComponent();
			HD msh5 = msh.getMsh5_ReceivingApplication();
			String destinationName = getValueOfHD(msh5);
			if (destinationName != null && !destinationName.isEmpty()) {
				messageDestination.setName(destinationName);
			}

			HD msh6 = msh.getMsh6_ReceivingFacility();
			String destinationEndpoint = getValueOfHD(msh6);
			if (destinationEndpoint != null && !destinationEndpoint.isEmpty()) {
				messageDestination.setEndpoint(destinationEndpoint);
			}

			if (!messageDestination.isEmpty()) {
				messageHeader.addDestination(messageDestination);
			}

			// messageHeader.timestamp from MSH-7
			TS msh7 = msh.getMsh7_DateTimeOfMessage();
			if (msh7 != null && !msh7.isEmpty()) {
				TSComponentOne timeOfEvent = msh7.getTs1_TimeOfAnEvent();
				if (timeOfEvent != null && !timeOfEvent.isEmpty()) {
					messageHeader.setTimestamp(timeOfEvent.getValueAsDate());
				}
			}

			// messageHeader.source from MSH-3
			HD msh3 = msh.getMsh3_SendingApplication();
			String sourceDestination = getValueOfHD(msh3);
			if (sourceDestination != null && !sourceDestination.isEmpty()) {
				MessageSourceComponent messageSource = new MessageSourceComponent();
				messageSource.setEndpoint(sourceDestination);
				messageHeader.setSource(messageSource);
			}

		} catch (HL7Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}

		return messageHeader;
	}

	public MessageHeader getMessageHeader() {
		// TODO Auto-generated method stub
		return null;
	}

}
