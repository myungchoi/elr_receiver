package edu.gatech.i3l.hl7.v2.elr_receiver;

import org.json.JSONArray;
import org.json.JSONObject;

public abstract class BaseHL7v2Parser implements IHL7v2Parser {
	protected String myVersion;
	
	protected int put_CE_to_json (Object element, JSONObject json_obj) {
		String system, code, display;
		String altSystem, altCode, altDisplay;
		int ret = 0;

		if (myVersion.equalsIgnoreCase("2.3")) {
			system = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getNameOfCodingSystem().getValueOrEmpty();
			code = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getIdentifier().getValueOrEmpty();
			display = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getText().getValueOrEmpty();
		} else
		if (myVersion.equalsIgnoreCase("2.3.1")) {
			system = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getNameOfCodingSystem().getValueOrEmpty();
			code = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getIdentifier().getValueOrEmpty();
			display = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getText().getValueOrEmpty();
		} else {
			system = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getNameOfCodingSystem().getValueOrEmpty();
			code = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getIdentifier().getValueOrEmpty();
			display = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getText().getValueOrEmpty();
		}
		
		if (myVersion.equalsIgnoreCase("2.3")) {
			altSystem = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getNameOfAlternateCodingSystem().getValueOrEmpty();
			altCode = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getAlternateIdentifier().getValueOrEmpty();
			altDisplay = ((ca.uhn.hl7v2.model.v23.datatype.CE)element).getAlternateText().getValueOrEmpty();
		} else
		if (myVersion.equalsIgnoreCase("2.3.1")) {
			altSystem = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getNameOfAlternateCodingSystem().getValueOrEmpty();
			altCode = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getAlternateIdentifier().getValueOrEmpty();
			altDisplay = ((ca.uhn.hl7v2.model.v231.datatype.CE)element).getAlternateText().getValueOrEmpty();
		} else {
			altSystem = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getNameOfAlternateCodingSystem().getValueOrEmpty();
			altCode = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getAlternateIdentifier().getValueOrEmpty();
			altDisplay = ((ca.uhn.hl7v2.model.v251.datatype.CE)element).getAlternateText().getValueOrEmpty();
		}

		if (
			(system.isEmpty() && code.isEmpty() && display.isEmpty() )
			||
			( ! system.equals("LN") && altSystem.equals("LN") )
			) {
			system = altSystem;
			code = altCode;
			display = altDisplay;
		}
		json_obj.put("System", system);
		json_obj.put("Code", code);
		json_obj.put("Display", display);	
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
	
	public abstract JSONObject map_patient_visit(Object obj);
	
}
