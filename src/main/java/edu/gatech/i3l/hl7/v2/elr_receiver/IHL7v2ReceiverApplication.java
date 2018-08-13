package edu.gatech.i3l.hl7.v2.elr_receiver;

import org.json.JSONObject;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;

public interface IHL7v2ReceiverApplication extends ReceivingApplication<Message> {
	public void sendData(JSONObject jsonData);
}
