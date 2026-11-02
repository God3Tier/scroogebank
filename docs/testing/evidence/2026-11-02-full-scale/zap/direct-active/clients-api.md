# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 3 |
| Informational | 3 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Medium | Exceeded High | https://alb.sg.itsag3t6.com | Percentage of responses with status code 4xx | 99 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of responses with status code 2xx | 1 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type application/json | 48 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type text/plain | 52 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method DELETE | 18 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 60 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method POST | 18 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method PUT | 4 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 50    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Cross-Origin-Resource-Policy Header Missing or Invalid | Low | 1 |
| Strict-Transport-Security Header Not Set | Low | Systemic |
| X-Content-Type-Options Header Missing | Low | 1 |
| A Client Error response code was returned by the server | Informational | 52 |
| Non-Storable Content | Informational | Systemic |
| Re-examine Cache-control Directives | Informational | 1 |




## Alert Detail



### [ Cross-Origin-Resource-Policy Header Missing or Invalid ](https://www.zaproxy.org/docs/alerts/90004/)



##### Low (Medium)

### Description

Cross-Origin-Resource-Policy header is an opt-in header designed to counter side-channels attacks like Spectre. Resource should be specifically set as shareable amongst different origins.

* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: `Cross-Origin-Resource-Policy`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``


Instances: 1

### Solution

Ensure that the application/web server sets the Cross-Origin-Resource-Policy header appropriately, and that it sets the Cross-Origin-Resource-Policy header to 'same-origin' for all web pages.
'same-site' is considered as less secured and should be avoided.
If resources must be shared, set the header to 'cross-origin'.
If possible, ensure that the end user uses a standards-compliant and modern web browser that supports the Cross-Origin-Resource-Policy header (https://caniuse.com/mdn-http_headers_cross-origin-resource-policy).

### Reference


* [ https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy ](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy)


#### CWE Id: [ 693 ](https://cwe.mitre.org/data/definitions/693.html)


#### WASC Id: 14

#### Source ID: 3

### [ Strict-Transport-Security Header Not Set ](https://www.zaproxy.org/docs/alerts/10035/)



##### Low (High)

### Description

HTTP Strict Transport Security (HSTS) is a web security policy mechanism whereby a web server declares that complying user agents (such as a web browser) are to interact with it using only secure HTTPS connections (i.e. HTTP layered over TLS/SSL). HSTS is an IETF standards track protocol and is specified in RFC 6797.

* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``

Instances: Systemic


### Solution

Ensure that your web server, application server, load balancer, etc. is configured to enforce Strict-Transport-Security.

### Reference


* [ https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Strict_Transport_Security_Cheat_Sheet.html ](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Strict_Transport_Security_Cheat_Sheet.html)
* [ https://owasp.org/www-community/Security_Headers ](https://owasp.org/www-community/Security_Headers)
* [ https://en.wikipedia.org/wiki/HTTP_Strict_Transport_Security ](https://en.wikipedia.org/wiki/HTTP_Strict_Transport_Security)
* [ https://caniuse.com/stricttransportsecurity ](https://caniuse.com/stricttransportsecurity)
* [ https://datatracker.ietf.org/doc/html/rfc6797 ](https://datatracker.ietf.org/doc/html/rfc6797)


#### CWE Id: [ 319 ](https://cwe.mitre.org/data/definitions/319.html)


#### WASC Id: 15

#### Source ID: 3

### [ X-Content-Type-Options Header Missing ](https://www.zaproxy.org/docs/alerts/10021/)



##### Low (Medium)

### Description

The Anti-MIME-Sniffing header X-Content-Type-Options was not set to 'nosniff'. This allows older versions of Internet Explorer and Chrome to perform MIME-sniffing on the response body, potentially causing the response body to be interpreted and displayed as a content type other than the declared content type. Current (early 2014) and legacy versions of Firefox will use the declared content type (if one is set), rather than performing MIME-sniffing.

* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: `x-content-type-options`
  * Attack: ``
  * Evidence: ``
  * Other Info: `This issue still applies to error type pages (401, 403, 500, etc.) as those pages are often still affected by injection issues, in which case there is still concern for browsers sniffing pages away from their actual content type.
At "High" threshold this scan rule will not alert on client or server error responses.`


Instances: 1

### Solution

Ensure that the application/web server sets the Content-Type header appropriately, and that it sets the X-Content-Type-Options header to 'nosniff' for all web pages.
If possible, ensure that the end user uses a standards-compliant and modern web browser that does not perform MIME-sniffing at all, or that can be directed by the web application/web server to not perform MIME-sniffing.

### Reference


* [ https://learn.microsoft.com/en-us/previous-versions/windows/internet-explorer/ie-developer/compatibility/gg622941(v=vs.85) ](https://learn.microsoft.com/en-us/previous-versions/windows/internet-explorer/ie-developer/compatibility/gg622941(v=vs.85))
* [ https://owasp.org/www-community/Security_Headers ](https://owasp.org/www-community/Security_Headers)


#### CWE Id: [ 693 ](https://cwe.mitre.org/data/definitions/693.html)


#### WASC Id: 15

#### Source ID: 3

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 401 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://alb.sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://alb.sg.itsag3t6.com/latest/meta-data/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/metadata/instance
  * Node Name: `https://alb.sg.itsag3t6.com/metadata/instance`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/metadata/v1
  * Node Name: `https://alb.sg.itsag3t6.com/metadata/v1`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://alb.sg.itsag3t6.com/opc/v1/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://alb.sg.itsag3t6.com/opc/v2/instance/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://alb.sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com
  * Node Name: `https://alb.sg.itsag3t6.com`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/
  * Node Name: `https://alb.sg.itsag3t6.com/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/2922448894544889297
  * Node Name: `https://alb.sg.itsag3t6.com/2922448894544889297`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api
  * Node Name: `https://alb.sg.itsag3t6.com/api`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/
  * Node Name: `https://alb.sg.itsag3t6.com/api/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/2345538672549241101
  * Node Name: `https://alb.sg.itsag3t6.com/api/2345538672549241101`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=http%253A%252F%252Fwww.google.com%252F&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/8387811426201530812
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/8387811426201530812`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/actuator/health
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/4372402558770059800
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/4372402558770059800`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/3408547962039041082
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/3408547962039041082`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal
  * Node Name: `https://alb.sg.itsag3t6.com/internal`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/
  * Node Name: `https://alb.sg.itsag3t6.com/internal/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/6367338384119198615
  * Node Name: `https://alb.sg.itsag3t6.com/internal/6367338384119198615`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/4510163762640667751
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/4510163762640667751`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/8075853515540596512
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/8075853515540596512`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/8630372759475400958
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/8630372759475400958`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment/
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/ ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `415`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:rtobject)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/ ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `415`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/verify/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/verify/ ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments/ ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/ ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``


Instances: 52

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `PUT `
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

* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: `cache-control`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``


Instances: 1

### Solution

For secure content, ensure the cache-control HTTP header is set with "no-cache, no-store, must-revalidate". If an asset should be cached consider setting the directives "public, max-age, immutable".

### Reference


* [ https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#web-content-caching ](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#web-content-caching)
* [ https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control ](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control)
* [ https://grayduck.mn/2021/09/13/cache-control-recommendations/ ](https://grayduck.mn/2021/09/13/cache-control-recommendations/)


#### CWE Id: [ 525 ](https://cwe.mitre.org/data/definitions/525.html)


#### WASC Id: 13

#### Source ID: 3


