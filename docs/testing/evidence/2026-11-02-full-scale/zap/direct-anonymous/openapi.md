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
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with content type application/problem+json | 100 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method GET | 53 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method POST | 40 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Percentage of endpoints with method PUT | 6 % |
| Info | Informational | https://alb.sg.itsag3t6.com | Count of total endpoints | 15    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| A Client Error response code was returned by the server | Informational | 15 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 401 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://alb.sg.itsag3t6.com/api/alert-portfolios/accountId
  * Node Name: `https://alb.sg.itsag3t6.com/api/alert-portfolios/accountId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts%3Fstatus=NEW&minimumScore=1.2&cursor=cursor&limit=25
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts (cursor,limit,minimumScore,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/events%3FonlyOpen=false&limit=25&cursor=cursor
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/events (cursor,limit,onlyOpen)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/events/eventId
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/events/eventId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks%3Fstatus=OPEN&severity=LOW&assignedAgentSubject=assignedAgentSubject&cursor=cursor&limit=25
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks (assignedAgentSubject,cursor,limit,severity,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/history
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/history`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId/decisions
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId/decisions ()({decision,expectedVersion,note})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId/feedback
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId/feedback ()({rating,reasonCode,comment,expectedVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/events/eventId/actions
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/events/eventId/actions ()({decision,note,members:[{alertId,expectedVersion}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/accept
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/accept ()({expectedVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/complete
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/complete ()({expectedVersion,outcomeCode,note})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/dismiss
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks/taskId/dismiss ()({expectedVersion,reasonCode,note})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alert-portfolios/accountId
  * Node Name: `https://alb.sg.itsag3t6.com/api/alert-portfolios/accountId ()({expectedRevision,sourceLabel,positions:[{instrumentId,quantity,unitPrice,valuationCurrency,fxToSgd,valuedAt}]})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``


Instances: 15

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://alb.sg.itsag3t6.com/api/alerts%3Fstatus=NEW&minimumScore=1.2&cursor=cursor&limit=25
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts (cursor,limit,minimumScore,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/relationship-health/tasks%3Fstatus=OPEN&severity=LOW&assignedAgentSubject=assignedAgentSubject&cursor=cursor&limit=25
  * Node Name: `https://alb.sg.itsag3t6.com/api/relationship-health/tasks (assignedAgentSubject,cursor,limit,severity,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId/decisions
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId/decisions ()({decision,expectedVersion,note})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://alb.sg.itsag3t6.com/api/alerts/alertId/feedback
  * Node Name: `https://alb.sg.itsag3t6.com/api/alerts/alertId/feedback ()({rating,reasonCode,comment,expectedVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
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


