package edu.gatech.i3l.hl7.v2.elr_receiver;

import org.json.JSONArray;
import org.json.JSONObject;

public abstract class BaseHL7v2Parser implements IHL7v2Parser {
	protected String myVersion;
	
	protected int put_CE_to_json (Object element, JSONObject json_obj) {
		String System;
		String Code;
		String Display;
		int ret = 0;
		
		if (myVersion.equalsIgnoreCase("2.3.1")) {
			System = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getNameOfCodingSystem().getValueOrEmpty();
			Code = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getIdentifier().getValueOrEmpty();
			Display = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getText().getValueOrEmpty();
		} else {
			System = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getNameOfCodingSystem().getValueOrEmpty();
			Code = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getIdentifier().getValueOrEmpty();
			Display = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getText().getValueOrEmpty();
		}
		
		if (System.isEmpty() && Code.isEmpty() && Display.isEmpty()) {
			if (myVersion.equalsIgnoreCase("2.3.1")) {
				System = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getNameOfAlternateCodingSystem().getValueOrEmpty();
				Code = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getAlternateIdentifier().getValueOrEmpty();
				Display = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getAlternateText().getValueOrEmpty();
			} else {
				System = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getNameOfAlternateCodingSystem().getValueOrEmpty();
				Code = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getAlternateIdentifier().getValueOrEmpty();
				Display = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getAlternateText().getValueOrEmpty();
			}
			ret = -1;
		} 

		json_obj.put("System", System);
		json_obj.put("Code", Code);
		json_obj.put("Display", Display);	
		return ret;
	}

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
