# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 1 |
| Medium | 0 |
| Low | 2 |
| Informational | 4 |




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
| SQL Injection | High | 22 |
| Cross-Origin-Embedder-Policy Header Missing or Invalid | Low | 1 |
| Unexpected Content-Type was returned | Low | 37 |
| A Client Error response code was returned by the server | Informational | 39 |
| Modern Web Application | Informational | 1 |
| Non-Storable Content | Informational | Systemic |
| Re-examine Cache-control Directives | Informational | 2 |




## Alert Detail



### [ SQL Injection ](https://www.zaproxy.org/docs/alerts/40018/)



##### High (Medium)

### Description

SQL injection may be possible.

* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `address`
  * Attack: `688 Zaproxy Ridge OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [688 Zaproxy Ridge AND 1=1 -- ] and [688 Zaproxy Ridge OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `agent_id`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `city`
  * Attack: `East Romaineburgh OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [East Romaineburgh AND 1=1 -- ] and [East Romaineburgh OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `country`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `gender`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `phone_number`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients
  * Node Name: `https://sg.itsag3t6.com/api/clients ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code,agent_id})`
  * Method: `POST`
  * Parameter: `state`
  * Attack: `Oklahoma OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [Oklahoma AND 1=1 -- ] and [Oklahoma OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId/verify
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/verify ()({document_ids:[]})`
  * Method: `POST`
  * Parameter: `document_ids[0]`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: `client_ids[0]`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: `from_user_id`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/reassignments
  * Node Name: `https://sg.itsag3t6.com/api/clients/reassignments ()({to_user_id,from_user_id,client_ids:[]})`
  * Method: `POST`
  * Parameter: `to_user_id`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `address`
  * Attack: `688 Zaproxy Ridge OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [688 Zaproxy Ridge AND 1=1 -- ] and [688 Zaproxy Ridge OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `city`
  * Attack: `East Romaineburgh OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [East Romaineburgh AND 1=1 -- ] and [East Romaineburgh OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `country`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `date_of_birth`
  * Attack: `1970-01-01T00:00:00.001Z OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [1970-01-01T00:00:00.001Z AND 1=1 -- ] and [1970-01-01T00:00:00.001Z OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `email`
  * Attack: `zaproxy@example.com OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [zaproxy@example.com AND 1=1 -- ] and [zaproxy@example.com OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `first_name`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `gender`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `last_name`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `phone_number`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `postal_code`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/clients/clientId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId ()({first_name,last_name,date_of_birth,gender,email,phone_number,address,city,state,country,postal_code})`
  * Method: `PUT`
  * Parameter: `state`
  * Attack: `Oklahoma OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [Oklahoma AND 1=1 -- ] and [Oklahoma OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`


Instances: 22

### Solution

Do not trust client side input, even if there is client side validation in place.
In general, type check all data on the server side.
If the application uses JDBC, use PreparedStatement or CallableStatement, with parameters passed by '?'
If the application uses ASP, use ADO Command Objects with strong type checking and parameterized queries.
If database Stored Procedures can be used, use them.
Do *not* concatenate strings into queries in the stored procedure, or use 'exec', 'exec immediate', or equivalent functionality!
Do not create dynamic SQL queries using simple string concatenation.
Escape all data received from the client.
Apply an 'allow list' of allowed characters, or a 'deny list' of disallowed characters in user input.
Apply the principle of least privilege by using the least privileged database user possible.
In particular, avoid using the 'sa' or 'db-owner' database users. This does not eliminate SQL injection, but minimizes its impact.
Grant the minimum database access that is necessary for the application.

### Reference


* [ https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html ](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html)


#### CWE Id: [ 89 ](https://cwe.mitre.org/data/definitions/89.html)


#### WASC Id: 19

#### Source ID: 1

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
* URL: https://sg.itsag3t6.com/2977822281257884465
  * Node Name: `https://sg.itsag3t6.com/2977822281257884465`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/6710879300659458836
  * Node Name: `https://sg.itsag3t6.com/6710879300659458836`
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
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=0+AND+1%253D1+--+&size=20
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
* URL: https://sg.itsag3t6.com/internal/1837436167511761728
  * Node Name: `https://sg.itsag3t6.com/internal/1837436167511761728`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/3070504943010120665
  * Node Name: `https://sg.itsag3t6.com/internal/3070504943010120665`
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
* URL: https://sg.itsag3t6.com/internal/v1/6838941450295554686
  * Node Name: `https://sg.itsag3t6.com/internal/v1/6838941450295554686`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/7364644611003033091
  * Node Name: `https://sg.itsag3t6.com/internal/v1/7364644611003033091`
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
* URL: https://sg.itsag3t6.com/internal/v1/clients/7813195401107127738
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/7813195401107127738`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/9033170291224777854
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/9033170291224777854`
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
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/4915389462788919031
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/4915389462788919031`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/v1/clients/clientId/7707190158471907390
  * Node Name: `https://sg.itsag3t6.com/internal/v1/clients/clientId/7707190158471907390`
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
* URL: https://sg.itsag3t6.com/api/5057054413703782484
  * Node Name: `https://sg.itsag3t6.com/api/5057054413703782484`
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
* URL: https://sg.itsag3t6.com/api/clients%3Fpage=0+AND+1%253D1+--+&size=20
  * Node Name: `https://sg.itsag3t6.com/api/clients (page,size)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/3100253044804675727
  * Node Name: `https://sg.itsag3t6.com/api/clients/3100253044804675727`
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
* URL: https://sg.itsag3t6.com/api/clients/clientId/5208327273723935350
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/5208327273723935350`
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
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/3509442561922823606
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/3509442561922823606`
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
  * Evidence: `<script type="module" crossorigin src="/assets/index-z31iwcrp.js"></script>`
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
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/clients/clientId/documents/documentId
  * Node Name: `https://sg.itsag3t6.com/api/clients/clientId/documents/documentId`
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


