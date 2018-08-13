package edu.gatech.i3l.hl7.v2.parser.ecr;

import org.json.JSONObject;

public interface IHL7v2ECRParser {
	// Object should be ORU_R01_PATIENT or equivalent section to 
	// get patient information. 
	// Return 0 for success.
	int map_patient (Object obj, JSONObject ecr_json);
	
	// Object should be ORU_R01_ORDER_OBSERVATION or equivalent section
	// to get lab order information.
	JSONObject map_order_observation (Object obj);
	
	// Object should be OBX or equivalent segment to get
	// lab results.
	JSONObject map_lab_result(Object obj);
	
	// Object should be ORU_R01 message or equivalent to get 
	// sending application and sending facility information.
	// Return 0 for success.
	int map_provider_from_appfac(Object obj, JSONObject ecr_json);
}
