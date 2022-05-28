package edu.gatech.i3l.hl7.v2.parser.ecr;

import java.util.Date;

import org.json.JSONArray;
import org.json.JSONObject;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.primitive.IS;
import ca.uhn.hl7v2.model.v231.datatype.CX;
import ca.uhn.hl7v2.model.v231.datatype.HD;
import ca.uhn.hl7v2.model.v231.datatype.XAD;
import ca.uhn.hl7v2.model.v231.datatype.XCN;
import ca.uhn.hl7v2.model.v231.datatype.XON;
import ca.uhn.hl7v2.model.v231.datatype.CE;
import ca.uhn.hl7v2.model.v231.datatype.FN;
import ca.uhn.hl7v2.model.v231.datatype.ST;
import ca.uhn.hl7v2.model.v231.datatype.TN;
import ca.uhn.hl7v2.model.v231.datatype.TS;
import ca.uhn.hl7v2.model.v231.datatype.TSComponentOne;
import ca.uhn.hl7v2.model.v231.datatype.XPN;
import ca.uhn.hl7v2.model.v231.datatype.XTN;
import ca.uhn.hl7v2.model.v231.group.ORU_R01_ORCOBRNTEOBXNTECTI;
import ca.uhn.hl7v2.model.v231.group.ORU_R01_PIDPD1NK1NTEPV1PV2;
import ca.uhn.hl7v2.model.v231.segment.OBR;
import ca.uhn.hl7v2.model.v231.segment.OBX;
import ca.uhn.hl7v2.model.v231.segment.ORC;
import ca.uhn.hl7v2.model.v231.segment.PID;
import ca.uhn.hl7v2.model.v231.message.ORU_R01;
import ca.uhn.hl7v2.model.v231.segment.MSH;

public class HL7v231ECRParser extends BaseHL7v2ECRParser {
	public HL7v231ECRParser() {
		setMyVersion("2.3.1");
	}

	private JSONObject constructPatientIDfromPID34 (CX cxObject, String type) {
		JSONObject patient_json_id = new JSONObject();

		String patientID = cxObject.getID().getValue();
		patient_json_id.put("value", patientID);

		// Rest of Extended Composite ID are all optional. So, we get
		// them if available.
		ca.uhn.hl7v2.model.v231.datatype.IS pIdIdentifierTypeCode = cxObject.getIdentifierTypeCode();
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
		
		String address = addressXAD.getStreetAddress().getValueOrEmpty();
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
	
	public int map_patient(Object obj, JSONObject ecr_json) {
		ORU_R01_PIDPD1NK1NTEPV1PV2 patient = (ORU_R01_PIDPD1NK1NTEPV1PV2) obj;
		
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
		
		int totPID3 = pid_seg.getPatientIdentifierListReps();		
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
			FN f_name = patientName_xpn.getFamilyLastName();
			ST f_name_st = f_name.getFamilyName();
			ST f_name_prefix_st = f_name.getLastNamePrefix();
			
			patientName_last = f_name_st.getValueOrEmpty();
			String prefix = f_name_prefix_st.getValueOrEmpty();
			if (!prefix.isEmpty())
				patientName_last = prefix+" "+patientName_last;
			
			ST m_name = patientName_xpn.getGivenName();
			patientName_given = m_name.getValueOrEmpty();
			
			if (patientName_last.isEmpty() && patientName_given.isEmpty()) {
				continue;
			}
			
			ST f_name_initial = patientName_xpn.getMiddleInitialOrName();
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
				TSComponentOne timeOfAnEvent = dateTimeDOB.getTimeOfAnEvent();
				String DOB_str = timeOfAnEvent.getValue();
				if (DOB_str != null && !DOB_str.isEmpty())
					patient_json.put("Birth_Date", DOB_str);
			}
		} catch (HL7Exception e) {
			e.printStackTrace();
		}
		
		// Administrative Sex
		IS gender = pid_seg.getSex();
		patient_json.put("Sex", gender.getValueOrEmpty());

		// Race
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
		
		if (!System2Use.isEmpty() || !Code2Use.isEmpty() || !Display2Use.isEmpty()) {
			JSONObject patient_race = new JSONObject();
			patient_json.put("Race", patient_race);		
	
			patient_race.put("System", System2Use);
			patient_race.put("Code", Code2Use);
			patient_race.put("Display", Display2Use);
		}
		
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

	public JSONObject map_order_observation(Object obj) {
		ORU_R01_ORCOBRNTEOBXNTECTI orderObs = (ORU_R01_ORCOBRNTEOBXNTECTI) obj;
		
		// Create a lab order JSON and put it in the lab order array.
		JSONObject ecr_laborder_json = new JSONObject();
		
		// Get OBR and ORC information.
		OBR orderRequest = orderObs.getOBR();
		ORC common_order = orderObs.getORC();

		// Get lab order information. This is a coded element.
		CE labOrderCE = orderRequest.getUniversalServiceID();
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
		
			String providerFamily = orderingProviderXCN.getFamilyLastName().getFamilyName().getValueOrEmpty();
			String providerGiven = orderingProviderXCN.getGivenName().getValueOrEmpty();
			String providerInitial = orderingProviderXCN.getMiddleInitialOrName().getValueOrEmpty();
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
		
		// Observation Time
		Date observationTime;
		try {
			observationTime = orderRequest.getObservationDateTime().getTimeOfAnEvent().getValueAsDate();
			if (observationTime != null) {
				ecr_laborder_json.put("Date", observationTime);
			}
		} catch (DataTypeException e) {
			e.printStackTrace();
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
			XTN orderFacilityPhoneXTN = common_order.getOrderingFacilityPhoneNumber(i);
			
			// See if we have full phone number.
			TN phoneNumberST = orderFacilityPhoneXTN.getXtn1_9999999X99999CAnyText();
			if (phoneNumberST != null) {
				String phoneNumber = phoneNumberST.getValue();
				if (phoneNumber != null & !phoneNumber.isEmpty()) {
					ok_to_put = true;
					facility_json.put("Phone", phoneNumber);
					break;
				}
			}

			String country = orderFacilityPhoneXTN.getCountryCode().getValue();
			String area = orderFacilityPhoneXTN.getAreaCityCode().getValue();
			String local = orderFacilityPhoneXTN.getPhoneNumber().getValue();
			
			String phone = "";
			if (!country.isEmpty()) phone = country;
			if (!area.isEmpty()) phone += "-"+area;
			if (!local.isEmpty()) phone += "-"+local;
			
			if (!phone.isEmpty()) {
				ok_to_put = true;
				facility_json.put("Phone", phone);
			}
			
			if (!country.isEmpty() && !area.isEmpty() && !local.isEmpty()) {
				break;
			} else {
				String backward_phone = orderFacilityPhoneXTN.getXtn1_9999999X99999CAnyText().getValue();
				if (!backward_phone.isEmpty()) {
					ok_to_put = true;
					facility_json.put("Phone", backward_phone);
					break;
				}
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
					if (put_CE_to_json(unitCE, unit_json) == 0)
						labresult_json.put("Unit", unit_json);
				}
			}
//			}
		}
		
		// Put date: Not required by ECR. But, putting the date anyway...
		TS obxDate = obsResult.getDateTimeOfTheObservation();
		if (obxDate != null) {
			try {
				Date obxDateDate = obxDate.getTimeOfAnEvent().getValueAsDate();
				labresult_json.put("Date", obxDateDate.toString());
			} catch (DataTypeException e) {
				e.printStackTrace();
			}
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
	
	@Override
	public JSONObject map_patient_visit(Object obj) {
		JSONObject patient_visit_json = new JSONObject(); 
		return patient_visit_json;
	}	
}
