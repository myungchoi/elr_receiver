package edu.gatech.i3l.hl7.v2.elr_receiver;

import org.json.JSONArray;
import org.json.JSONObject;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.primitive.IS;
import ca.uhn.hl7v2.model.v23.datatype.CX;
import ca.uhn.hl7v2.model.v23.datatype.HD;
import ca.uhn.hl7v2.model.v23.datatype.XAD;
import ca.uhn.hl7v2.model.v23.datatype.XCN;
import ca.uhn.hl7v2.model.v23.datatype.XON;
import ca.uhn.hl7v2.model.v23.datatype.CE;
//import ca.uhn.hl7v2.model.v23.datatype.FN;
import ca.uhn.hl7v2.model.v23.datatype.ST;
import ca.uhn.hl7v2.model.v23.datatype.TS;
import ca.uhn.hl7v2.model.v23.datatype.TSComponentOne;
import ca.uhn.hl7v2.model.v23.datatype.XPN;
import ca.uhn.hl7v2.model.v23.datatype.XTN;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_RESPONSE;
import ca.uhn.hl7v2.model.v23.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v23.segment.OBR;
import ca.uhn.hl7v2.model.v23.segment.OBX;
import ca.uhn.hl7v2.model.v23.segment.ORC;
import ca.uhn.hl7v2.model.v23.segment.PID;
import ca.uhn.hl7v2.model.v23.message.ORU_R01;
import ca.uhn.hl7v2.model.v23.segment.MSH;

public class HL7v23Parser extends BaseHL7v2Parser {
	public HL7v23Parser() {
		myVersion = "2.3";
	}

	private JSONObject constructPatientIDfromPID34 (CX cxObject, String type) {
		JSONObject patient_json_id = new JSONObject();

		String patientID = cxObject.getID().getValue();
		patient_json_id.put("value", patientID);

		// Rest of Extended Composite ID are all optional. So, we get
		// them if available.
		ca.uhn.hl7v2.model.v23.datatype.IS pIdIdentifierTypeCode = cxObject.getIdentifierTypeCode();
		if (pIdIdentifierTypeCode != null) {
			String IdType = pIdIdentifierTypeCode.getValueOrEmpty();
			if (IdType.isEmpty()) {
				patient_json_id.put("type", type);
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
		
		int totPID3 = pid_seg.getPatientIDInternalIDReps();		
		for (int j=0; j<totPID3; j++) {
			CX pIdentifier = pid_seg.getPid3_PatientIDInternalID(j);
			patient_json_id_list.put(constructPatientIDfromPID34(pIdentifier, "PATIENT_IDENTIFIER"));
		}

		int totPatientNames = pid_seg.getPid5_PatientNameReps();
		for (int j=0; j<totPatientNames; j++) {
			XPN patientName_xpn = pid_seg.getPid5_PatientName(j);
			ST f_name_st = patientName_xpn.getFamilyName();
			ST f_name_prefix_st = patientName_xpn.getPrefixEgDR();
			
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
		TS dateTimeDOB = pid_seg.getDateOfBirth();
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
		IS race = pid_seg.getRace();
		if ( race != null ) {
			JSONObject patient_race = new JSONObject();
			patient_json.put("Race", patient_race);		
	
			patient_race.put("Code", race.getValue());
			patient_race.put("Display", race.getValue());
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
		IS ethnicity = pid_seg.getEthnicGroup();
		if ( ethnicity != null ) {
			patient_json.put("Ethnicity", patient_ethnicity);
		}
		
		return 0;
	}

	public JSONObject map_order_observation(Object obj) {
		ORU_R01_ORDER_OBSERVATION orderObs = (ORU_R01_ORDER_OBSERVATION) obj;
		
		// Create a lab order JSON and put it in the lab order array.
		JSONObject ecr_laborder_json = new JSONObject();
		
		// Get OBR and ORC information.
		OBR orderRequest = orderObs.getOBR();
		ORC common_order = orderObs.getORC();

		// Get lab order information. This is a coded element.
		CE labOrderCE = orderRequest.getObr4_UniversalServiceIdentifier();			// UniversalServiceID();
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
		
			String providerFamily = orderingProviderXCN.getFamilyName().getValue();
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
		
		if (ok_to_put) ecr_laborder_json.put("Provider", provider_json);		
		
		// Observation Time
//		String observationTime = orderRequest.getObservationDateTime().getTimeOfAnEvent().getValue();
//		if (observationTime != null && !observationTime.isEmpty())
//			ecr_laborder_json.put("DateTime", observationTime);
		
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
		/*
		int totalFacilityNames = common_order.getOrdering
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
		int totalFacilityAddress = common_order.getOrderingProviderAddressReps();
		for (int i=0; i<totalFacilityAddress; i++) {
			XAD addressXAD = common_order.getOrderingProviderAddress(i);
			String address = make_single_address (addressXAD);
			if (address=="") continue;
			ok_to_put = true;
			facility_json.put("Address", address);
			break;
		}
		*/
		
		if (ok_to_put) ecr_laborder_json.put("Facility", facility_json);

		return ecr_laborder_json;
	}

	public JSONObject map_lab_result(Object obj) {
		ORU_R01_OBSERVATION obsResult = (ORU_R01_OBSERVATION) obj;
		
		JSONObject labresult_json = new JSONObject();
		
		CE obsCE = obsResult.getOBX().getObservationIdentifier();
		put_CE_to_json(obsCE, labresult_json);
			
		// Add Value and Unit if appropriate.
		String valueType = obsResult.getOBX().getValueType().getValueOrEmpty();
		if (!valueType.isEmpty()) {
//			if (valueType.equalsIgnoreCase("NM") 
//					|| valueType.equalsIgnoreCase("ST")
//					|| valueType.equalsIgnoreCase("TX")) {
			int totalValues = obsResult.getOBX().getObservationValueReps();
			if (totalValues > 0) {
				Varies obsValue = obsResult.getOBX().getObservationValue(0);
				labresult_json.put("Value", obsValue.getData());
				CE unitCE = obsResult.getOBX().getUnits();
				if (unitCE != null) {
					JSONObject unit_json = new JSONObject();
					if (put_CE_to_json(unitCE, unit_json) == 0)
						labresult_json.put("Unit", unit_json);
				}
			}
//			}
		}
		
		// Put date: Not required by ECR. But, putting the date anyway...
		TS obxDate = obsResult.getOBX().getDateTimeOfTheObservation();
		if (obxDate != null) {
			labresult_json.put("Date", obxDate.getTimeOfAnEvent().getValue());
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
