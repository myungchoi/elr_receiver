# elr_receiver
ELR Receiver to receive HL7 v2 message from MLLP and HTTP to ECR and FHIR


Java application that listens to
- MLLP to Electronic Case Report (ECR) in JSON (GT-custom ECR data format)
- HTTP to FHIR STU3


Use config.property to set the variable. Or, environment variables as shown below,
| Environment Variables    | Example     |
| ------------------------ | ----------- |
| ECR_URL | http://host:8080/ecr |
| FHIR_URL | http://host:8080/fhir |
| HTTP_AUTH_USER | HTTP basic username |
| HTTP_AUTH_PASSWORD | HTTP basic password |
| INDEX_SERVER_USER | HTTP basic username |
| INDEX_SERVER_PASSWORD | HTTP basic password |
| PARSER_MODE | ECR or FHIR |
| PATIENT_INDEX_SERVER | http://host:8080/decedent-index |
| TRANSPORT_MODE | MLLP or HTTP |


If HTTP is used for a transport mode, then HTTP POST must be used with Content-Type: application/hl7-v2

