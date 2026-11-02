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
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of responses with status code 4xx | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type application/json | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 6    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Server Leaks Version Information via "Server" HTTP Response Header Field | Low | Systemic |
| Strict-Transport-Security Header Not Set | Low | Systemic |
| A Client Error response code was returned by the server | Informational | 6 |
| Storable and Cacheable Content | Informational | Systemic |




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

* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
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
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
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
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
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


Instances: 6

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Storable and Cacheable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are storable by caching components such as proxy servers, and may be retrieved directly from the cache, rather than from the origin server by the caching servers, in response to similar requests from other users. If the response data is sensitive, personal or user-specific, this may result in sensitive information being leaked. In some cases, this may even result in a user gaining complete control of the session of another user, depending on the configuration of the caching components in use in their environment. This is primarily an issue where "shared" caching servers such as "proxy" caches are configured on the local network. This configuration is typically found in corporate or educational environments, for instance.

* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/ids
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/ids`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: `In the absence of an explicitly specified caching lifetime directive in the response, a liberal lifetime heuristic of 1 year was assumed. This is permitted by rfc7234.`
* URL: https://alb.sg.itsag3t6.com/aggregates/accounts/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/accounts/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: `In the absence of an explicitly specified caching lifetime directive in the response, a liberal lifetime heuristic of 1 year was assumed. This is permitted by rfc7234.`
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/action-items%3FstaleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/action-items (staleDays)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: `In the absence of an explicitly specified caching lifetime directive in the response, a liberal lifetime heuristic of 1 year was assumed. This is permitted by rfc7234.`
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/stats%3Ffrom=2026-09-01&to=2026-09-30&groupBy=agent
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/stats (from,groupBy,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: `In the absence of an explicitly specified caching lifetime directive in the response, a liberal lifetime heuristic of 1 year was assumed. This is permitted by rfc7234.`
* URL: https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison%3Fdays=30
  * Node Name: `https://alb.sg.itsag3t6.com/aggregates/clients/team-comparison (days)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: ``
  * Other Info: `In the absence of an explicitly specified caching lifetime directive in the response, a liberal lifetime heuristic of 1 year was assumed. This is permitted by rfc7234.`

Instances: Systemic


### Solution

Validate that the response does not contain sensitive, personal or user-specific information. If it does, consider the use of the following HTTP response headers, to limit, or prevent the content being stored and retrieved from the cache by another user:
Cache-Control: no-cache, no-store, must-revalidate, private
Pragma: no-cache
Expires: 0
This configuration directs both HTTP 1.0 and HTTP 1.1 compliant caching servers to not store the response, and to not retrieve the response (without validation) from the cache, in response to a similar request.

### Reference


* [ https://datatracker.ietf.org/doc/html/rfc7234 ](https://datatracker.ietf.org/doc/html/rfc7234)
* [ https://datatracker.ietf.org/doc/html/rfc7231 ](https://datatracker.ietf.org/doc/html/rfc7231)
* [ https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html ](https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html)


#### CWE Id: [ 524 ](https://cwe.mitre.org/data/definitions/524.html)


#### WASC Id: 13

#### Source ID: 3


