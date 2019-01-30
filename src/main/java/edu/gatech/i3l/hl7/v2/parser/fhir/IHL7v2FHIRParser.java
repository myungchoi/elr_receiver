package edu.gatech.i3l.hl7.v2.parser.fhir;

import java.util.List;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.MessageHeader;

import ca.uhn.hl7v2.model.Message;

public interface IHL7v2FHIRParser {
	public List<Bundle> executeParser(Message msg);
	public MessageHeader getMessageHeader();
	public String getReceivingFacilityName();
}
