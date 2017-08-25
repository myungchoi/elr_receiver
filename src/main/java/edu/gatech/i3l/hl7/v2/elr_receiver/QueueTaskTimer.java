package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.util.TimerTask;

public class QueueTaskTimer extends TimerTask {
	private HL7v2ReceiverApplication myApp = null;
	
	public QueueTaskTimer(HL7v2ReceiverApplication app) {
		this.myApp = app;
	}
	
	@Override
	public void run() {
		if (myApp != null) {
			myApp.process_q();
		}
	}

}
