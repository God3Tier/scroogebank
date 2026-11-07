# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 2 |
| Informational | 5 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Medium | Exceeded High | https://sg.itsag3t6.com | Percentage of responses with status code 4xx | 95 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of responses with status code 2xx | 4 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type application/json | 9 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/html | 54 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/plain | 36 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method DELETE | 16 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method GET | 63 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method POST | 16 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method PUT | 3 % |
| Info | Informational | https://sg.itsag3t6.com | Count of total endpoints | 55    |
| Info | Informational | https://sg.itsag3t6.com | Percentage of slow responses | 1 % |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Cross-Origin-Embedder-Policy Header Missing or Invalid | Low | 1 |
| Unexpected Content-Type was returned | Low | 37 |
| A Client Error response code was returned by the server | Informational | 39 |
| Modern Web Application | Informational | 1 |
| Non-Storable Content | Informational | Systemic |
| Re-examine Cache-control Directives | Informational | 2 |
| Retrieved from Cache | Informational | 1 |




## Alert Detail



### [ Cross-Origin-Embedder-Policy Header Missing or Invalid ](https://www.zaproxy.org/docs/alerts/90004/)



##### Low (Medium)

### Description

Cross-Origin-Embedder-Policy header is a response header that prevents a document from loading any cross-origin resources that don't explicitly grant the document permission (using CORP or CORS).

* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: `Cross-Origin-Embedder-Policy`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``


Instances: 1

### Solution

Ensure that the application/web server sets the Cross-Origin-Embedder-Policy header appropriately, and that it sets the Cross-Origin-Embedder-Policy header to 'require-corp' for documents.
If possible, ensure that the end user uses a standards-compliant and modern web browser that supports the Cross-Origin-Embedder-Policy header (https://caniuse.com/mdn-http_headers_cross-origin-embedder-policy).

### Reference


* [ https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy ](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy)


#### CWE Id: [ 693 ](https://cwe.mitre.org/data/definitions/693.html)


#### WASC Id: 14

#### Source ID: 3

### [ Unexpected Content-Type was returned ](https://www.zaproxy.org/docs/alerts/100001/)



##### Low (High)

### Description

A Content-Type of text/html was returned by the server.
This is not one of the types expected to be returned by an API.
Raised by the 'Alert on Unexpected Content Types' script

* URL: https://sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://sg.itsag3t6.com/latest/meta-data/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/instance
  * Node Name: `https://sg.itsag3t6.com/metadata/instance`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/v1
  * Node Name: `https://sg.itsag3t6.com/metadata/v1`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v1/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v2/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com
  * Node Name: `https://sg.itsag3t6.com`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/
  * Node Name: `https://sg.itsag3t6.com/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/1256607942477775126
  * Node Name: `https://sg.itsag3t6.com/1256607942477775126`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/5395993342455170747
  * Node Name: `https://sg.itsag3t6.com/5395993342455170747`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api
  * Node Name: `https://sg.itsag3t6.com/api`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=type+%2525SYSTEMROOT%2525%255Cwin.ini&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal
  * Node Name: `https://sg.itsag3t6.com/internal`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/
  * Node Name: `https://sg.itsag3t6.com/internal/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/6771738768732767029
  * Node Name: `https://sg.itsag3t6.com/internal/6771738768732767029`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/8220907781357321409
  * Node Name: `https://sg.itsag3t6.com/internal/8220907781357321409`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1
  * Node Name: `https://sg.itsag3t6.com/internal/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/
  * Node Name: `https://sg.itsag3t6.com/internal/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/3741036670520005956
  * Node Name: `https://sg.itsag3t6.com/internal/v1/3741036670520005956`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/6340240353288542669
  * Node Name: `https://sg.itsag3t6.com/internal/v1/6340240353288542669`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/6461082037255957189
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/6461082037255957189`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/8856402830914899008
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/8856402830914899008`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/205060392785677131
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/205060392785677131`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/3992850591028545126
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/3992850591028545126`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment/
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:rtobject)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``


Instances: 37

### Solution



### Reference




#### Source ID: 4

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 400 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://sg.itsag3t6.com/latest/meta-data/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/instance
  * Node Name: `https://sg.itsag3t6.com/metadata/instance`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/v1
  * Node Name: `https://sg.itsag3t6.com/metadata/v1`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v1/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v2/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/
  * Node Name: `https://sg.itsag3t6.com/api/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/912650131340131739
  * Node Name: `https://sg.itsag3t6.com/api/912650131340131739`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=http%253A%252F%252Fwww.google.com%252F&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=type+%2525SYSTEMROOT%2525%255Cwin.ini&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/7225633992326009902
  * Node Name: `https://sg.itsag3t6.com/api/clients/7225633992326009902`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/actuator/health
  * Node Name: `https://sg.itsag3t6.com/api/clients/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/5856305252956134317
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/5856305252956134317`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/6072556394064068803
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/6072556394064068803`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/documentId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/documentId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/documentId/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/documentId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/
  * Node Name: `https://sg.itsag3t6.com/api/clients/ ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `415`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:rtobject)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/ ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `415`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/verify/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/verify/ ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/reassignments/
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments/ ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/ ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``


Instances: 39

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Modern Web Application ](https://www.zaproxy.org/docs/alerts/10109/)



##### Informational (Medium)

### Description

The application appears to be a modern web application. If you need to explore it automatically then the Client Spider may well be more effective than the standard one.

* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="module" crossorigin src="/assets/index-CuKYXTJ8.js"></script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`


Instances: 1

### Solution

This is an informational alert and so no changes are required.

### Reference




#### Source ID: 3

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `DELETE `
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``

Instances: Systemic


### Solution

The content may be marked as storable by ensuring that the following conditions are satisfied:
The request method must be understood by the cache and defined as being cacheable ("GET", "HEAD", and "POST" are currently defined as cacheable)
The response status code must be understood by the cache (one of the 1XX, 2XX, 3XX, 4XX, or 5XX response classes are generally understood)
The "no-store" cache directive must not appear in the request or response header fields
For caching by "shared" caches such as "proxy" caches, the "private" response directive must not appear in the response
For caching by "shared" caches such as "proxy" caches, the "Authorization" header field must not appear in the request, unless the response explicitly allows it (using one of the "must-revalidate", "public", or "s-maxage" Cache-Control response directives)
In addition to the conditions above, at least one of the following conditions must also be satisfied by the response:
It must contain an "Expires" header field
It must contain a "max-age" response directive
For "shared" caches such as "proxy" caches, it must contain a "s-maxage" response directive
It must contain a "Cache Control Extension" that allows it to be cached
It must have a status code that is defined as cacheable by default (200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501).

### Reference


* [ https://datatracker.ietf.org/doc/html/rfc7234 ](https://datatracker.ietf.org/doc/html/rfc7234)
* [ https://datatracker.ietf.org/doc/html/rfc7231 ](https://datatracker.ietf.org/doc/html/rfc7231)
* [ https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html ](https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html)


#### CWE Id: [ 524 ](https://cwe.mitre.org/data/definitions/524.html)


#### WASC Id: 13

#### Source ID: 3

### [ Re-examine Cache-control Directives ](https://www.zaproxy.org/docs/alerts/10015/)



##### Informational (Low)

### Description

The cache-control header has not been set properly or is missing, allowing the browser and proxies to cache content. For static assets like css, js, or image files this might be intended, however, the resources should be reviewed to ensure that no sensitive content will be cached.

* URL: https://sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: `cache-control`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: `cache-control`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``


Instances: 2

### Solution

For secure content, ensure the cache-control HTTP header is set with "no-cache, no-store, must-revalidate". If an asset should be cached consider setting the directives "public, max-age, immutable".

### Reference


* [ https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#web-content-caching ](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#web-content-caching)
* [ https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control ](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control)
* [ https://grayduck.mn/2021/09/13/cache-control-recommendations/ ](https://grayduck.mn/2021/09/13/cache-control-recommendations/)


#### CWE Id: [ 525 ](https://cwe.mitre.org/data/definitions/525.html)


#### WASC Id: 13

#### Source ID: 3

### [ Retrieved from Cache ](https://www.zaproxy.org/docs/alerts/10050/)



##### Informational (Medium)

### Description

The content was retrieved from a shared cache. If the response data is sensitive, personal or user-specific, this may result in sensitive information being leaked. In some cases, this may even result in a user gaining complete control of the session of another user, depending on the configuration of the caching components in use in their environment. This is primarily an issue where caching servers such as "proxy" caches are configured on the local network. This configuration is typically found in corporate or educational environments, for instance.

* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Hit from cloudfront`
  * Other Info: ``


Instances: 1

### Solution

Validate that the response does not contain sensitive, personal or user-specific information. If it does, consider the use of the following HTTP response headers, to limit, or prevent the content being stored and retrieved from the cache by another user:
Cache-Control: no-cache, no-store, must-revalidate, private
Pragma: no-cache
Expires: 0
This configuration directs both HTTP 1.0 and HTTP 1.1 compliant caching servers to not store the response, and to not retrieve the response (without validation) from the cache, in response to a similar request.

### Reference


* [ https://datatracker.ietf.org/doc/html/rfc7234 ](https://datatracker.ietf.org/doc/html/rfc7234)
* [ https://datatracker.ietf.org/doc/html/rfc7231 ](https://datatracker.ietf.org/doc/html/rfc7231)
* [ https://www.rfc-editor.org/rfc/rfc9110.html ](https://www.rfc-editor.org/rfc/rfc9110.html)


#### CWE Id: [ 525 ](https://cwe.mitre.org/data/definitions/525.html)


#### Source ID: 3


