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
| Info | Informational | https://sg.itsag3t6.com | Percentage of responses with status code 2xx | 6 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of responses with status code 4xx | 93 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type application/json | 25 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/html | 75 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method GET | 100 % |
| Info | Informational | https://sg.itsag3t6.com | Count of total endpoints | 16    |
| Info | Informational | https://sg.itsag3t6.com | Percentage of slow responses | 1 % |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Unexpected Content-Type was returned | Low | 14 |
| A Client Error response code was returned by the server | Informational | 12 |
| Non-Storable Content | Informational | 1 |




## Alert Detail



### [ Unexpected Content-Type was returned ](https://www.zaproxy.org/docs/alerts/100001/)



##### Low (High)

### Description

A Content-Type of text/html was returned by the server.
This is not one of the types expected to be returned by an API.
Raised by the 'Alert on Unexpected Content Types' script

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
* URL: https://sg.itsag3t6.com/6374997106517139217
  * Node Name: `https://sg.itsag3t6.com/6374997106517139217`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/8783928645419600697
  * Node Name: `https://sg.itsag3t6.com/8783928645419600697`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/actuator/health
  * Node Name: `https://sg.itsag3t6.com/actuator/health`
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
* URL: https://sg.itsag3t6.com/api/activities%3FagentId=type+%2525SYSTEMROOT%2525%255Cwin.ini&clientId=clientId&from=from&to=to&size=20&cursor=cursor
  * Node Name: `https://sg.itsag3t6.com/api/activities (agentId,clientId,cursor,from,size,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://sg.itsag3t6.com/latest/meta-data/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/instance
  * Node Name: `https://sg.itsag3t6.com/metadata/instance`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/v1
  * Node Name: `https://sg.itsag3t6.com/metadata/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v1/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v2/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``


Instances: 14

### Solution



### Reference




#### Source ID: 4

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 400 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://sg.itsag3t6.com/api/
  * Node Name: `https://sg.itsag3t6.com/api/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/7023877015568840839
  * Node Name: `https://sg.itsag3t6.com/api/7023877015568840839`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/activities%3FagentId=agentId&clientId=clientId&from=from&to=to&size=20&cursor=cursor
  * Node Name: `https://sg.itsag3t6.com/api/activities (agentId,clientId,cursor,from,size,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/activities%3FagentId=type+%2525SYSTEMROOT%2525%255Cwin.ini&clientId=clientId&from=from&to=to&size=20&cursor=cursor
  * Node Name: `https://sg.itsag3t6.com/api/activities (agentId,clientId,cursor,from,size,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/activities/
  * Node Name: `https://sg.itsag3t6.com/api/activities/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://sg.itsag3t6.com/latest/meta-data/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/instance
  * Node Name: `https://sg.itsag3t6.com/metadata/instance`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/metadata/v1
  * Node Name: `https://sg.itsag3t6.com/metadata/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v1/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://sg.itsag3t6.com/opc/v2/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``


Instances: 12

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://sg.itsag3t6.com/api/activities%3FagentId=agentId&clientId=clientId&from=from&to=to&size=20&cursor=cursor
  * Node Name: `https://sg.itsag3t6.com/api/activities (agentId,clientId,cursor,from,size,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``


Instances: 1

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


