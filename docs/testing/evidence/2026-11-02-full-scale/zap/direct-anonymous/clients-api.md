# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 1 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of responses with status code 4xx | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type application/json | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method DELETE | 9 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 45 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method POST | 36 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method PUT | 9 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 11    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Strict-Transport-Security Header Not Set | Low | Systemic |
| A Client Error response code was returned by the server | Informational | 11 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



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
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId`
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
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients%3Fpage=0&size=20
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents/documentId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
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
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/documents ()(multipart:file)`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``


Instances: 11

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
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/clientId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment
  * Node Name: `https://alb.sg.itsag3t6.com/internal/v1/clients/clientId/assignment`
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
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://alb.sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
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


