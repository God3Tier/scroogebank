# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 1 |
| Medium | 0 |
| Low | 1 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Medium | Exceeded High | https://sg.itsag3t6.com | Percentage of responses with status code 4xx | 90 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of responses with status code 2xx | 9 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type application/json | 15 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/html | 71 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/plain | 13 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method DELETE | 17 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method GET | 63 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method PATCH | 3 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method POST | 11 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method PUT | 3 % |
| Info | Informational | https://sg.itsag3t6.com | Count of total endpoints | 52    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| SQL Injection | High | 8 |
| Unexpected Content-Type was returned | Low | 41 |
| A Client Error response code was returned by the server | Informational | 31 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



### [ SQL Injection ](https://www.zaproxy.org/docs/alerts/40018/)



##### High (Medium)

### Description

SQL injection may be possible.

* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId ()({status})`
  * Method: `PATCH`
  * Parameter: `status`
  * Attack: `ACTIVE OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [ACTIVE AND 1=1 -- ] and [ACTIVE OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `account_type`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `branch_id`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `client_id`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `currency`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `initial_deposit`
  * Attack: `1.2" OR "1"="1" -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [1.2" AND "1"="1" -- ] and [1.2" OR "1"="1" -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `opening_date`
  * Attack: `1970-01-01T00:00:00.001Z OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [1970-01-01T00:00:00.001Z AND 1=1 -- ] and [1970-01-01T00:00:00.001Z OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: `status`
  * Attack: `John Doe OR 1=1 -- `
  * Evidence: ``
  * Other Info: `The page results were successfully manipulated using the boolean conditions [John Doe AND 1=1 -- ] and [John Doe OR 1=1 -- ]
The parameter value being modified was stripped from the HTML output for the purposes of the comparison.
Data was NOT returned for the original parameter.
The vulnerability was detected by successfully retrieving more data than originally returned, by manipulating the parameter.`


Instances: 8

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
* URL: https://sg.itsag3t6.com/1591880430478478517
  * Node Name: `https://sg.itsag3t6.com/1591880430478478517`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/2239897165431894367
  * Node Name: `https://sg.itsag3t6.com/2239897165431894367`
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
* URL: https://sg.itsag3t6.com/api/accounts%3FclientId=clientId+AND+1%253D1+--+
  * Node Name: `https://sg.itsag3t6.com/api/accounts (clientId)`
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
* URL: https://sg.itsag3t6.com/internal/6322707198963295593
  * Node Name: `https://sg.itsag3t6.com/internal/6322707198963295593`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/8423780175662027447
  * Node Name: `https://sg.itsag3t6.com/internal/8423780175662027447`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts
  * Node Name: `https://sg.itsag3t6.com/internal/accounts`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/1415684547397715569
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/1415684547397715569`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/449785257779029180
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/449785257779029180`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts
  * Node Name: `https://sg.itsag3t6.com/internal/alerts`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/2222292183746713206
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/2222292183746713206`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/4269635380100153046
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/4269635380100153046`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/7632911819331757993
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/7632911819331757993`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/7675879474526729645
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/7675879474526729645`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership
  * Node Name: `https://sg.itsag3t6.com/ownership`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/
  * Node Name: `https://sg.itsag3t6.com/ownership/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/1833087589433666227
  * Node Name: `https://sg.itsag3t6.com/ownership/1833087589433666227`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/7384692142568374952
  * Node Name: `https://sg.itsag3t6.com/ownership/7384692142568374952`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId ()({status})`
  * Method: `PATCH`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/lookup
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/lookup ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/lookup/
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/lookup/ ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/lookup
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/lookup ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/lookup/
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/lookup/ ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/accounts
  * Node Name: `https://sg.itsag3t6.com/ownership/accounts ()({client_ids:[],owner_agent_id})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/accounts/
  * Node Name: `https://sg.itsag3t6.com/ownership/accounts/ ()({client_ids:[],owner_agent_id})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``


Instances: 41

### Solution



### Reference




#### Source ID: 4

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 400 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId/
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
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
* URL: https://sg.itsag3t6.com/api/2880107717898417886
  * Node Name: `https://sg.itsag3t6.com/api/2880107717898417886`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts%3FclientId=clientId
  * Node Name: `https://sg.itsag3t6.com/api/accounts (clientId)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts%3FclientId=clientId+AND+1%253D1+--+
  * Node Name: `https://sg.itsag3t6.com/api/accounts (clientId)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/
  * Node Name: `https://sg.itsag3t6.com/api/accounts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/1050734329845051123
  * Node Name: `https://sg.itsag3t6.com/api/accounts/1050734329845051123`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId/
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/actuator/health
  * Node Name: `https://sg.itsag3t6.com/api/accounts/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId ()({status})`
  * Method: `PATCH`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId ()({status})`
  * Method: `PATCH`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId/
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId/ ()({status})`
  * Method: `PATCH`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/
  * Node Name: `https://sg.itsag3t6.com/api/accounts/ ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/lookup
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/lookup ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/accounts/lookup/
  * Node Name: `https://sg.itsag3t6.com/internal/accounts/lookup/ ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/lookup
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/lookup ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/internal/alerts/accounts/lookup/
  * Node Name: `https://sg.itsag3t6.com/internal/alerts/accounts/lookup/ ()({ids:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/accounts
  * Node Name: `https://sg.itsag3t6.com/ownership/accounts ()({client_ids:[],owner_agent_id})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/accounts/
  * Node Name: `https://sg.itsag3t6.com/ownership/accounts/ ()({client_ids:[],owner_agent_id})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``


Instances: 31

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `DELETE `
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts/accountId
  * Node Name: `https://sg.itsag3t6.com/api/accounts/accountId ()({status})`
  * Method: `PATCH`
  * Parameter: ``
  * Attack: ``
  * Evidence: `PATCH `
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/accounts
  * Node Name: `https://sg.itsag3t6.com/api/accounts ()({client_id,account_type,status,opening_date,initial_deposit,currency,branch_id})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/ownership/accounts
  * Node Name: `https://sg.itsag3t6.com/ownership/accounts ()({client_ids:[],owner_agent_id})`
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


