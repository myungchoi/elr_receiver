package edu.gatech.i3l.hl7.v2.parser;

import org.json.JSONObject;

public class BaseHL7v2Parser {
	protected String myVersion;
	
	public String getMyVersion() {
		return this.myVersion;
	}
	
	public void setMyVersion(String myVersion) {
		this.myVersion = myVersion;
	}	
}
