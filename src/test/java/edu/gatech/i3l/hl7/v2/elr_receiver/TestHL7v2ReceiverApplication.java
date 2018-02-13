package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v251.segment.ECR;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import gatech.edu.STIECR.JSON.utils.ECRJsonConverter;
import junit.framework.TestCase;

public class TestHL7v2ReceiverApplication extends TestCase {

	public void testProcessMessage() throws IOException, HL7Exception, ReceivingApplicationException {
		HapiContext context = new DefaultHapiContext();
		
		Parser p = context.getPipeParser();
		context.setModelClassFactory(new CanonicalModelClassFactory("2.5.1"));
		
		String msgStr = new String(Files.readAllBytes(new File("p:/FHIR_ECR_DATA/DATA_FROM_LOINC_ENHANCED_2017_1201_1208/ecr_positive_ln_1emsg.txt").toPath()));
		msgStr = msgStr.replaceAll("\n", "\r");
		Message hl7Msg = p.parse(msgStr);
		
		final List<JSONObject> results = new ArrayList<JSONObject>(); 
		HL7v2ReceiverApplication app = new HL7v2ReceiverApplication() {
			@Override
			void send_ecr(JSONObject ecrJson) throws Exception {
				results.add(ecrJson);
			}
		};
		app.canProcess(hl7Msg);
		app.processMessage(hl7Msg, new HashMap() );
		
		assertEquals(1, results.size());
		System.out.println("HL7.ECR=\n" + results.get(0).toString(3));
		
		ECRJsonConverter converter = new ECRJsonConverter();
		
		gatech.edu.STIECR.JSON.ECR ecr = converter.convertToEntityAttribute(results.get(0).toString(3));
		
		assertNotNull(ecr);
		
	}
}
