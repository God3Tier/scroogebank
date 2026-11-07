# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 0 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of responses with status code 4xx | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 5    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| A Client Error response code was returned by the server | Informational | 5 |
| Non-Storable Content | Informational | 5 |




## Alert Detail



### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 401 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://alb.sg.itsag3t6.com/api/dashboard/action-items%3Ffrom=2026-08-21&to=2026-09-20&staleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/action-items (from,staleDays,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/agents%3Ffrom=2026-08-21&to=2026-09-20&sort=clientsOnboarded&currency=SGD
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/agents (currency,from,sort,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/overview%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/overview (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/portfolio%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/portfolio (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/transactions%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/transactions (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``


Instances: 5

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://alb.sg.itsag3t6.com/api/dashboard/action-items%3Ffrom=2026-08-21&to=2026-09-20&staleDays=7
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/action-items (from,staleDays,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/agents%3Ffrom=2026-08-21&to=2026-09-20&sort=clientsOnboarded&currency=SGD
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/agents (currency,from,sort,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/overview%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/overview (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/portfolio%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/portfolio (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/dashboard/transactions%3Ffrom=2026-08-21&to=2026-09-20
  * Node Name: `https://alb.sg.itsag3t6.com/api/dashboard/transactions (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``


Instances: 5

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


