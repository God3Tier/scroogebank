# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 2 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Medium | Exceeded High | https://alb.sg.itsag3t6.com | Percentage of responses with status code 4xx | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type application/json | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 34    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Server Leaks Version Information via "Server" HTTP Response Header Field | Low | Systemic |
| Strict-Transport-Security Header Not Set | Low | Systemic |
| A Client Error response code was returned by the server | Informational | 35 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



### [ Server Leaks Version Information via "Server" HTTP Response Header Field ](https://www.zaproxy.org/docs/alerts/10036/)



##### Low (High)

### Description

The web/application server is leaking version information via the "Server" HTTP response header. Access to such information may facilitate attackers identifying other vulnerabilities your web/application server is subject to.

* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `awselb/2.0`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `awselb/2.0`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `awselb/2.0`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `awselb/2.0`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `awselb/2.0`
  * Other Info: ``

Instances: Systemic


### Solution

Ensure that your web server, application server, load balancer, etc. is configured to suppress the "Server" header or provide generic details.

### Reference


* [ https://httpd.apache.org/docs/current/mod/core.html#servertokens ](https://httpd.apache.org/docs/current/mod/core.html#servertokens)
* [ https://learn.microsoft.com/en-us/previous-versions/msp-n-p/ff648552(v=pandp.10) ](https://learn.microsoft.com/en-us/previous-versions/msp-n-p/ff648552(v=pandp.10))
* [ https://www.troyhunt.com/shhh-dont-let-your-response-headers/ ](https://www.troyhunt.com/shhh-dont-let-your-response-headers/)


#### CWE Id: [ 497 ](https://cwe.mitre.org/data/definitions/497.html)


#### WASC Id: 13

#### Source ID: 3

### [ Strict-Transport-Security Header Not Set ](https://www.zaproxy.org/docs/alerts/10035/)



##### Low (High)

### Description

HTTP Strict Transport Security (HSTS) is a web security policy mechanism whereby a web server declares that complying user agents (such as a web browser) are to interact with it using only secure HTTPS connections (i.e. HTTP layered over TLS/SSL). HSTS is an IETF standards track protocol and is specified in RFC 6797.

* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
  * Method: `GET`
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

A response code of 404 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

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
* URL: https://alb.sg.itsag3t6.com/6911858182659471867
  * Node Name: `https://alb.sg.itsag3t6.com/6911858182659471867`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/actuator/health
  * Node Name: `https://alb.sg.itsag3t6.com/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/7539394650806825990
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/7539394650806825990`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/4221295909697314621
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/4221295909697314621`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/573353756331944048
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/573353756331944048`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/transactions
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/transactions`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/transactions/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/transactions/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/transactions/5017260741278593675
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/transactions/5017260741278593675`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/transactions/stats%3Ffrom=2026-09-01&to=2026-09-30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/transactions/stats (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/transactions/stats/
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/transactions/stats/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/computeMetadata/v1/
  * Node Name: `https://alb.sg.itsag3t6.com/computeMetadata/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/latest/meta-data/
  * Node Name: `https://alb.sg.itsag3t6.com/latest/meta-data/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/metadata/instance
  * Node Name: `https://alb.sg.itsag3t6.com/metadata/instance`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/metadata/v1
  * Node Name: `https://alb.sg.itsag3t6.com/metadata/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/opc/v1/instance/
  * Node Name: `https://alb.sg.itsag3t6.com/opc/v1/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/opc/v2/instance/
  * Node Name: `https://alb.sg.itsag3t6.com/opc/v2/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/openstack/latest/meta_data.json
  * Node Name: `https://alb.sg.itsag3t6.com/openstack/latest/meta_data.json`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``


Instances: 35

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `authorization:`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
  * Method: `GET`
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


