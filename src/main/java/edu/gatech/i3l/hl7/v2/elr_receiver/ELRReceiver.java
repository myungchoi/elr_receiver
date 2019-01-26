package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.ConnectionListener;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.hoh.llp.Hl7OverHttpLowerLayerProtocol;
import ca.uhn.hl7v2.hoh.util.ServerRoleEnum;
import ca.uhn.hl7v2.llp.LowerLayerProtocol;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationExceptionHandler;

/**
 * HL7v2 Receiver This application will listen to ELR sent from laboratory. The
 * ELR should be mapped to ECR and sent to PHCR controller using /ECR Post API.
 *
 * version: 0.0.1 last updated: 7/21/2017
 * 
 * License:
 */
public class ELRReceiver {
	// Logger setup
	final static Logger LOGGER = Logger.getLogger(ELRReceiver.class.getName());

	static String default_port = "8888";
	static String default_phcr_controller_api_url = "http://localhost:8888/ECR";
	static String default_fhir_controller_api_url = "http://localhost:8080/fhir";
	static String default_receiver_parser_mode = "FHIR";
	static boolean default_useTls = false;
	static String default_useTls_str = "False";
	static String default_qFileName = "queueELR";
	static String default_ecrTemplateFileName = "ECR.json";
	static String default_transport = "MLLP";
	static String default_httpAuth = "user:password";

	public static void main(String[] args) throws Exception {
		Properties prop = new Properties();
		OutputStream output = null;
		InputStream input = null;
		Integer port = Integer.parseInt(default_port);
		boolean useTls = default_useTls;
		String phcr_controller_api_url = default_phcr_controller_api_url;
		String fhir_controller_api_url = default_fhir_controller_api_url;
		String parser_mode = default_receiver_parser_mode;
		String qFileName = default_qFileName;
		String ecrTemplateFileName = default_ecrTemplateFileName;
		String transport = default_transport;
		String httpAuth = default_httpAuth;

		boolean writeConfig = false;
		try {
			input = new FileInputStream("config.properties");
			prop.load(input);

			port = Integer.parseInt(prop.getProperty("port", default_port));
			phcr_controller_api_url = prop.getProperty("phcrControllerUrl", default_phcr_controller_api_url);
			fhir_controller_api_url = prop.getProperty("fhirControllerUrl", default_fhir_controller_api_url);
			parser_mode = prop.getProperty("HL7ParserMode", default_receiver_parser_mode);
			qFileName = prop.getProperty("qFileName", default_qFileName);
			ecrTemplateFileName = prop.getProperty("ecrFileName", default_ecrTemplateFileName);
			transport = prop.getProperty("transport", default_transport);
			httpAuth = prop.getProperty("HTTPAuth", default_httpAuth);

			if (prop.getProperty("useTls", default_useTls_str).equalsIgnoreCase("true")) {
				useTls = true;
			} else {
				useTls = false;
			}
		} catch (Exception e) {
			writeConfig = true;
			e.printStackTrace();
		} finally {
			if (writeConfig) {
				output = new FileOutputStream("config.properties");
				prop.setProperty("port", default_port);
				prop.setProperty("phcrControllerUrl", default_phcr_controller_api_url);
				prop.setProperty("fhirControllerUrl", default_fhir_controller_api_url);
				prop.setProperty("HL7ParserMode", default_receiver_parser_mode);
				prop.setProperty("useTls", default_useTls_str);
				prop.setProperty("qFileName", default_qFileName);
				prop.setProperty("ecrFileName", default_ecrTemplateFileName);
				prop.setProperty("transport", default_transport);
				prop.setProperty("HTTPAuth", default_httpAuth);
				prop.store(output, null);
			}
		}
		
		// End point configuration override by environment variable.
		String envEcrUrl = System.getenv("ECR_URL");
		if (envEcrUrl != null && !envEcrUrl.isEmpty()) {
			if (!envEcrUrl.startsWith("http://") && !envEcrUrl.startsWith("https://")) {
				envEcrUrl = "http://"+envEcrUrl;
			}
			
			if (envEcrUrl.endsWith("/")) {
				envEcrUrl = envEcrUrl.substring(0, envEcrUrl.length()-1);
			}
			
			phcr_controller_api_url = envEcrUrl;
		}

		String envFhirUrl = System.getenv("FHIR_URL");
		if (envFhirUrl != null && !envFhirUrl.isEmpty()) {
			if (!envFhirUrl.startsWith("http://") && !envFhirUrl.startsWith("https://")) {
				envFhirUrl = "http://"+envFhirUrl;
			}
			
			if (envFhirUrl.endsWith("/")) {
				envFhirUrl = envFhirUrl.substring(0, envFhirUrl.length()-1);
			}
			
			fhir_controller_api_url = envFhirUrl;
		}
		
		// parser mode override by environment variable
		String envParserMode = System.getenv("PARSER_MODE");
		if (envParserMode != null && !envParserMode.isEmpty()) {
			parser_mode = envParserMode;
		}
		
		// transport mode override by environment variable
		String envTransport = System.getenv("TRANSPORT_MODE");
		if (envTransport != null && !envTransport.isEmpty()) {
			transport = envTransport;
		}

		if ("MLLP".equals(transport)) {
			HapiContext ctx = new DefaultHapiContext();
			LOGGER.debug("Starting with MLLP");
			HL7Service server = ctx.newServer(port, useTls);

			if (parser_mode.equals("FHIR")) {
				LOGGER.debug("Preparing for FHIR parser");
				HL7v2ReceiverFHIRApplication handler = new HL7v2ReceiverFHIRApplication();
				server.registerApplication("*", "*", (ReceivingApplication<Message>) handler);
				// Configure the Receiver App before we start.
				handler.config(fhir_controller_api_url, useTls, qFileName, ecrTemplateFileName, null);
			} else {
				LOGGER.debug("Preparing for ECR parser");
				HL7v2ReceiverECRApplication handler = new HL7v2ReceiverECRApplication();
				server.registerApplication("*", "*", (ReceivingApplication<Message>) handler);
				// Configure the Receiver App before we start.
				handler.config(phcr_controller_api_url, useTls, qFileName, ecrTemplateFileName, null);
			}
			server.registerConnectionListener(new MyConnectionListener());
			server.setExceptionHandler(new MyExceptionHandler());

			server.startAndWait();
			LOGGER.debug("MLLP server started");
		} else {
			LOGGER.debug("Starting with HTTP");
			LowerLayerProtocol llp;
			llp = new Hl7OverHttpLowerLayerProtocol(ServerRoleEnum.SERVER);

			PipeParser parser = PipeParser.getInstanceWithNoValidation();
			SimpleServer server = new SimpleServer(port, llp, parser);
			server.setExceptionHandler(new MyExceptionHandler());

			if (parser_mode.equals("FHIR")) {
				HL7v2ReceiverFHIRApplication handler = new HL7v2ReceiverFHIRApplication();
				((Hl7OverHttpLowerLayerProtocol) llp).setAuthorizationCallback(handler);

				server.registerApplication("*", "*", (ReceivingApplication<Message>) handler);
				// Configure the Receiver App before we start.
				handler.config(fhir_controller_api_url, useTls, qFileName, ecrTemplateFileName, httpAuth);
			} else {
				HL7v2ReceiverECRApplication handler = new HL7v2ReceiverECRApplication();
				((Hl7OverHttpLowerLayerProtocol) llp).setAuthorizationCallback(handler);

				server.registerApplication("*", "*", (ReceivingApplication<Message>) handler);
				// Configure the Receiver App before we start.
				handler.config(phcr_controller_api_url, useTls, qFileName, ecrTemplateFileName, httpAuth);
			}

			server.registerConnectionListener(new MyConnectionListener());
			server.setExceptionHandler(new MyExceptionHandler());

			server.start();
			LOGGER.debug("HTTP server started");
		}

	}

	public static class MyConnectionListener implements ConnectionListener {

		public void connectionDiscarded(Connection theC) {
			LOGGER.info("Lost connection from: " + theC.getRemoteAddress().toString());
		}

		public void connectionReceived(Connection theC) {
			LOGGER.info("New connection received: " + theC.getRemoteAddress().toString());
		}

	}

	/**
	 * Process an exception.
	 * 
	 * @param theIncomingMessage  the incoming message. This is the raw message
	 *                            which was received from the external system
	 * @param theIncomingMetadata Any metadata that accompanies the incoming
	 *                            message. See
	 *                            {@link ca.uhn.hl7v2.protocol.Transportable#getMetadata()}
	 * @param theOutgoingMessage  the outgoing message. The response NAK message
	 *                            generated by HAPI.
	 * @param theE                the exception which was received
	 * @return The new outgoing message. This can be set to the value provided by
	 *         HAPI in <code>outgoingMessage</code>, or may be replaced with another
	 *         message. <b>This method may not return <code>null</code></b>.
	 */
	public static class MyExceptionHandler implements ReceivingApplicationExceptionHandler {

		public String processException(String theIncomingMessage, Map<String, Object> theIncomingMetadata,
				String theOutgoingMessage, Exception theE) throws HL7Exception {
			LOGGER.error("processException(incoming):\n" + theIncomingMessage + "\n\n");
			LOGGER.error("processException(outgoing):\n" + theOutgoingMessage + "\n\n");
			LOGGER.error("Exception:", theE);
			return theOutgoingMessage;
		}

	}

}
