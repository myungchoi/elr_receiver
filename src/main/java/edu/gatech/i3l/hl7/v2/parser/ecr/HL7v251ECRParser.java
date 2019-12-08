package edu.gatech.i3l.hl7.v2.parser.ecr;

import java.util.Date;

import org.json.JSONArray;
import org.json.JSONObject;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.primitive.IS;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.FN;
import ca.uhn.hl7v2.model.v251.datatype.HD;
import ca.uhn.hl7v2.model.v251.datatype.ID;
import ca.uhn.hl7v2.model.v251.datatype.PL;
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
import ca.uhn.hl7v2.model.v251.group.ORU_R01_VISIT;
//import ca.uhn.hl7v2.model.v251.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import ca.uhn.hl7v2.model.v251.segment.MSH;
import ca.uhn.hl7v2.model.v251.segment.OBR;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import ca.uhn.hl7v2.model.v251.segment.ORC;
import ca.uhn.hl7v2.model.v251.segment.PID;
import ca.uhn.hl7v2.model.v251.segment.PV1;
import ca.uhn.hl7v2.model.v251.segment.STF;

public class HL7v251ECRParser extends BaseHL7v2ECRParser {
	public HL7v251ECRParser() {
		setMyVersion("2.5.1");
	}

	private JSONObject constructPatientIDfromPID34 (CX cxObject, String type) {
		JSONObject patient_json_id = new JSONObject();

		String patientID = cxObject.getIDNumber().getValue();
		patient_json_id.put("value", patientID);

		// Rest of Extended Composite ID are all optional. So, we get
		// them if available.
		ID pIdIdentifierTypeCode = cxObject.getIdentifierTypeCode();
		if (pIdIdentifierTypeCode != null) {
			String IdType = pIdIdentifierTypeCode.getValueOrEmpty();
			if (IdType.isEmpty()) {
				patient_json_id.put("type", "MR");
			} else {
				patient_json_id.put("type", IdType);
			}
		}

//		HD pIdAssignAuth = cxObject.getAssigningAuthority();
//		if (pIdAssignAuth != null) {
//			String AssignAuthName = pIdAssignAuth.getNamespaceID().getValueOrEmpty();
//
//			// Patient ID Number and Assigning Authority Name Space (user defined)
//			// will probably sufficient to check.
//			if (!AssignAuthName.isEmpty()) {
////				if (AssignAuthName.equalsIgnoreCase("EMR")) {
////					break;
////				}
//				patient_json_id.put("type", AssignAuthName);
//			} else {
//				patient_json_id.put("type", type);
//			}
//		}
		
		return patient_json_id;
	}
	
	private String make_single_address (XAD addressXAD) {
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
	public int map_patient(Object obj, JSONObject ecr_json) {
		ORU_R01_PATIENT patient = (ORU_R01_PATIENT) obj;
		
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
		// We store PID-3 and PID-4
		PID pid_seg = patient.getPID();
		JSONArray patient_json_id_list = new JSONArray();
		patient_json.put("ID", patient_json_id_list);
		
		int totPID3 = pid_seg.getPid3_PatientIdentifierListReps();		
		for (int j=0; j<totPID3; j++) {
			CX pIdentifier = pid_seg.getPid3_PatientIdentifierList(j);
			patient_json_id_list.put(constructPatientIDfromPID34(pIdentifier, "PATIENT_IDENTIFIER"));
		}

		int totPID4 = pid_seg.getPid4_AlternatePatientIDPIDReps();
		
		for (int j=0; j<totPID4; j++) {
			CX pAlternateID = pid_seg.getPid4_AlternatePatientIDPID(j);
			patient_json_id_list.put(constructPatientIDfromPID34(pAlternateID, "ALTERNATIVE_PATIENT_ID"));
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
		if (patient_json.getJSONArray("ID").length() == 0 
				&& (patientName_given.isEmpty() && patientName_last.isEmpty()))
			return 0;
		
		// Set all the collected patient information to ECR
		JSONObject patient_name = new JSONObject();
		patient_json.put("Name", patient_name);
		
		if (!patientName_middle.isEmpty()) 
			patientName_given += " "+patientName_middle;
		
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
		
		return 0;
	}
	
	public JSONObject map_patient_visit(Object obj) {
		JSONObject facility = new JSONObject();
		
		try {
			ca.uhn.hl7v2.model.v251.message.ORU_R01 oru_msg = (ca.uhn.hl7v2.model.v251.message.ORU_R01)obj;
			
			ORU_R01_PATIENT_RESULT patient_result = oru_msg.getPATIENT_RESULT();
			
			ORU_R01_VISIT visit = patient_result.getPATIENT().getVISIT();
			
			PV1 pv1 = visit.getPV1();
			
			PL patientLoc_pl = pv1.getAssignedPatientLocation();
			
			String hospSvcName = patientLoc_pl.getBuilding().getValue();

			STF stf0 = (STF)visit.get("STF");

			XAD[] stf0add = stf0.getOfficeHomeAddressBirthplace();
			
			XAD stf0add0 = stf0add[0];
			
//			String hospSvcAddrDwellingNumber = stf0add0.getStreetAddress().getDwellingNumber().getValue();
//			String hospSvcAddrStreetName = stf0add0.getStreetAddress().getStreetOrMailingAddress().getValue();
//			String hospSvcAddrCity = stf0add0.getCity().getValue();
//			String hospSvcAddrState = stf0add0.getStateOrProvince().getValue();
//			String hospSvcAddrZip = stf0add0.getZipOrPostalCode().getValue();
			
			facility.put("Name", hospSvcName);
//			facility.put("Address", 
//					String.format("%s %s, %s, %s %s",
//							hospSvcAddrDwellingNumber==null ? "" : hospSvcAddrDwellingNumber,
//							hospSvcAddrStreetName==null ? "" : hospSvcAddrStreetName,
//							hospSvcAddrCity==null ? "" : hospSvcAddrCity,
//							hospSvcAddrState==null ? "" : hospSvcAddrState,
//							hospSvcAddrZip==null ? "" : hospSvcAddrZip));
			
		} catch (HL7Exception e) {
			e.printStackTrace();
		}
		
		return facility;

//		ORU_R01_VISIT visit = patient.getVISIT();
//		
//		String visitFacility = visit.getNames()[0];
//		String visitCity = visit.
		
		
	}

	/*
	 *   ORDER OBSERVATION List
	 *   OBR-16. Use ORC-12 if OBR-16 is empty.
	 */
	public JSONObject map_order_observation(Object obj) {
		ORU_R01_ORDER_OBSERVATION orderObs = (ORU_R01_ORDER_OBSERVATION) obj;
		
		// Create a lab order JSON and put it in the lab order array.
		JSONObject ecr_laborder_json = new JSONObject();
		
		// Get OBR and ORC information.
		OBR orderRequest = orderObs.getOBR();
		ORC common_order = orderObs.getORC();

		// Get lab order information. This is a coded element.
		CE labOrderCE = orderRequest.getUniversalServiceIdentifier();
		put_CE_to_json (labOrderCE, ecr_laborder_json);

		JSONObject provider_json = new JSONObject();
		// To support LAB where ELR is coming from laboratory,
		// we have provider information in OBR and ORC. We check if we OBR has
		// a provider information. If not, we try with ORC.
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
			
			JSONObject orderingProvider_json_ID = new JSONObject();
			String orderingProviderID = orderingProviderXCN.getIDNumber().getValueOrEmpty();
			if (!orderingProviderID.isEmpty()) {
				orderingProvider_json_ID.put("value", orderingProviderID);
				orderingProvider_json_ID.put("type", "ORDPROVIDER");
				provider_json.put("ID", orderingProvider_json_ID);
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
		
		// Provider Address
		// There may be multiple provider addresses. We get only one.
		int totalProviderAddress = common_order.getOrderingProviderAddressReps();
		for (int i=0; i<totalProviderAddress; i++) {
			XAD addressXAD = common_order.getOrderingProviderAddress(i);
			String address = make_single_address (addressXAD);
			if (address=="") continue;
			ok_to_put = true;
			provider_json.put("Address", address);
			break;
		}
		
		if (ok_to_put) ecr_laborder_json.put("Provider", provider_json);		
		
		// Observation Requested Time
//		String observationTime = orderRequest.getObservationDateTime().getTime().getValue();
		Date observationRequestedTime;
		try {
			observationRequestedTime = orderRequest.getRequestedDateTime().getTime().getValueAsDate();
			ecr_laborder_json.put("Date", observationRequestedTime);
		} catch (DataTypeException e1) {
			e1.printStackTrace();
		}
		
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
			try {
				XTN orderFacilityPhoneXTN = common_order.getOrderingFacilityPhoneNumber(i);
				
				// See if we have full phone number.
				if ( orderFacilityPhoneXTN != null ) {
					ST phoneNumberST = orderFacilityPhoneXTN.getTelephoneNumber();
					if (phoneNumberST != null) {
						String phoneNumber = phoneNumberST.getValue();
						if (phoneNumber != null && !phoneNumber.isEmpty()) {
							ok_to_put = true;
							facility_json.put("Phone", phoneNumber);
							break;
						}
					}
				}
				
				String country = orderFacilityPhoneXTN.getCountryCode().getValue();
				String area = orderFacilityPhoneXTN.getAreaCityCode().getValue();
				String local = orderFacilityPhoneXTN.getLocalNumber().getValue();
				
				String phone = "";
				if (country!=null && !country.isEmpty()) phone = country;
				if (area!=null && !area.isEmpty()) phone += "-"+area;
				if (local!=null && !local.isEmpty()) phone += "-"+local;
				
				if (phone!=null && !phone.isEmpty()) {
					ok_to_put = true;
					facility_json.put("Phone", phone);
				}
				
				if (country!=null && !country.isEmpty() && area!=null && !area.isEmpty() && local!=null && !local.isEmpty()) {
					break;
				} else {
					String backward_phone = orderFacilityPhoneXTN.getTelephoneNumber().getValueOrEmpty();
					if (!backward_phone.isEmpty()) {
						ok_to_put = true;
						facility_json.put("Phone", backward_phone);
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		// Facility Address
		// There may be multiple facility addresses. We get only one.
		int totalFacilityAddress = common_order.getOrderingFacilityAddressReps();
		for (int i=0; i<totalFacilityAddress; i++) {
			XAD addressXAD = common_order.getOrderingFacilityAddress(i);
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
	public JSONObject map_lab_result(Object obj) {
		OBX obsResult = (OBX) obj;
		
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
					if (put_CE_to_json(unitCE, unit_json) == 0) {
						labresult_json.put("Unit", unit_json);
					}
				}
			}
//			}
		}
		
		// Put date: Not required by ECR. But, putting the date anyway...
		Date obxDate = null;
		try {
			obxDate = obsResult.getDateTimeOfTheObservation().getTime().getValueAsDate();
		} catch (DataTypeException e) {
			e.printStackTrace();
			obxDate = null;
		}
		
		if (obxDate == null) {
			try {
				obxDate = obsResult.getEffectiveDateOfReferenceRangeValues().getTime().getValueAsDate();
			} catch (DataTypeException e) {
				e.printStackTrace();
				obxDate = null;
			}			
		} 

		if (obxDate == null) {
			try {
				obxDate = obsResult.getDateTimeOfTheAnalysis().getTime().getValueAsDate();
			} catch (DataTypeException e) {
				e.printStackTrace();
				obxDate = null;
			}	
		}	

		if (obxDate != null) {
			labresult_json.put("Date", obxDate);
		} 
		
		return labresult_json;
	}

	public int map_provider_from_appfac(Object obj, JSONObject ecr_json) {
		ORU_R01 msg = (ORU_R01) obj;
		
		String appNamespaceID = "";
		String appUniversalID = "";
		String appUniversalIDType = "";
		
		MSH msh = msg.getMSH();
		HD sendingAppHD = msh.getSendingApplication();
		if (sendingAppHD != null) {
			appNamespaceID = sendingAppHD.getNamespaceID().getValueOrEmpty();
			appUniversalID = sendingAppHD.getUniversalID().getValueOrEmpty();
			appUniversalIDType = sendingAppHD.getUniversalIDType().getValueOrEmpty();
		}
		
		String sendingApp = "";
		if (!appNamespaceID.isEmpty())
			sendingApp = appNamespaceID+" ";
		if (!appUniversalID.isEmpty())
			sendingApp += appUniversalID+" ";
		if (!appUniversalIDType.isEmpty())
			sendingApp += appUniversalIDType;
		
		sendingApp = sendingApp.trim();
		
		ecr_json.put("Sending Application", sendingApp);
		
		// Provider: We collect from multiple places 
		// To support HIE where ELR is coming from hospital, we
		// use sending application and facility as a provider.
		String facNamespaceID = "";
		HD sendingFacHD = msh.getSendingFacility();
		if (sendingFacHD != null) {
			facNamespaceID = sendingFacHD.getNamespaceID().getValueOrEmpty();
		}
		
		String provider_string = appNamespaceID+"|"+facNamespaceID;
		
		JSONObject provider_json_id = new JSONObject();
		provider_json_id.put("value", provider_string);
		provider_json_id.put("type", "appfac");
		JSONObject provider_json = new JSONObject();
		provider_json.put("ID", provider_json_id);
		
		add_provider(provider_json, ecr_json);

		return 0;
	}

}
