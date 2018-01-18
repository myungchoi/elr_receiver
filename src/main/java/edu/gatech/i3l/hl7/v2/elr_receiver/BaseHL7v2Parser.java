package edu.gatech.i3l.hl7.v2.elr_receiver;

import org.json.JSONArray;
import org.json.JSONObject;

public abstract class BaseHL7v2Parser implements IHL7v2Parser {
	
	protected void add_provider(JSONObject provider, JSONObject ecr_json) {
		JSONArray providers_json;
		
		if (ecr_json.isNull("Provider")) {
			providers_json = new JSONArray();
			ecr_json.put("Provider", providers_json);
		} else {
			providers_json = ecr_json.getJSONArray("Provider");
		}

		providers_json.put(provider);
	}
}
