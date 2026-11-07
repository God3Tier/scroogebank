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
| Medium | Exceeded High | https://sg.itsag3t6.com | Percentage of responses with status code 4xx | 97 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of responses with status code 2xx | 2 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type application/json | 79 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with content type text/html | 20 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method DELETE | 20 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method GET | 61 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method POST | 5 % |
| Info | Informational | https://sg.itsag3t6.com | Percentage of endpoints with method PUT | 12 % |
| Info | Informational | https://sg.itsag3t6.com | Count of total endpoints | 54    |
| Info | Informational | https://sg.itsag3t6.com | Percentage of slow responses | 1 % |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Unexpected Content-Type was returned | Low | 21 |
| A Client Error response code was returned by the server | Informational | 57 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



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
* URL: https://sg.itsag3t6.com/5240330831678626774
  * Node Name: `https://sg.itsag3t6.com/5240330831678626774`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/7735006367092416095
  * Node Name: `https://sg.itsag3t6.com/7735006367092416095`
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
* URL: https://sg.itsag3t6.com/api/engagement/drafts%3Fstatus=type+%2525SYSTEMROOT%2525%255Cwin.ini&clientId=clientId&limit=50
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts (clientId,limit,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events%3Ffrom=type+%2525SYSTEMROOT%2525%255Cwin.ini&to=2026-11-20T00%253A00%253A00+08%253A00&includeCancelled=false
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events (from,includeCancelled,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3Fcountry=type+%2525SYSTEMROOT%2525%255Cwin.ini&from=2026-10-01&to=2026-12-31
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (country,from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/coverage%3Fcountry=type+%2525SYSTEMROOT%2525%255Cwin.ini
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/coverage (country)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId ()({subject,body,version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `text/html`
  * Other Info: ``


Instances: 21

### Solution



### Reference




#### Source ID: 4

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 400 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId/`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
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
* URL: https://sg.itsag3t6.com/api/1542202564986038409
  * Node Name: `https://sg.itsag3t6.com/api/1542202564986038409`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement
  * Node Name: `https://sg.itsag3t6.com/api/engagement`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/6717636467443274894
  * Node Name: `https://sg.itsag3t6.com/api/engagement/6717636467443274894`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts%3Fstatus=DRAFT&clientId=clientId&limit=50
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts (clientId,limit,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts%3Fstatus=type+%2525SYSTEMROOT%2525%255Cwin.ini&clientId=clientId&limit=50
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts (clientId,limit,status)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/8180624627847927134
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/8180624627847927134`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/actuator/health
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events%3Ffrom=2026-10-20T00:00:00+08:00&to=2026-11-20T00:00:00+08:00&includeCancelled=false
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events (from,includeCancelled,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events%3Ffrom=type+%2525SYSTEMROOT%2525%255Cwin.ini&to=2026-11-20T00%253A00%253A00+08%253A00&includeCancelled=false
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events (from,includeCancelled,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/8666729257863106131
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/8666729257863106131`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3Fcountry=SG&from=2026-10-01&=
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (country,from)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3Fcountry=http%253A%252F%252Fwww.google.com%252F&from=2026-10-01&to=2026-12-31
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (country,from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3Fcountry=type+%2525SYSTEMROOT%2525%255Cwin.ini&from=2026-10-01&to=2026-12-31
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (country,from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3Fcountry=SG&=&to=2026-12-31
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (country,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays%3F=&from=2026-10-01&to=2026-12-31
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/8286213510178399032
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/8286213510178399032`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/coverage%3F=
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/coverage`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/coverage%3Fcountry=http%253A%252F%252Fwww.google.com%252F
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/coverage (country)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/coverage%3Fcountry=type+%2525SYSTEMROOT%2525%255Cwin.ini
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/coverage (country)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/holidays/coverage/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/holidays/coverage/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/preferences/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/preferences/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/ ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId ()({subject,body,version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId ()({subject,body,version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/drafts/draftId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/drafts/draftId/ ()({subject,body,version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId/ ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}],version})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/preferences/
  * Node Name: `https://sg.itsag3t6.com/api/engagement/preferences/ ()({invitesEnabled,remindersEnabled})`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `403`
  * Other Info: ``


Instances: 57

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId`
  * Method: `DELETE`
  * Parameter: ``
  * Attack: ``
  * Evidence: `DELETE `
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events%3Ffrom=2026-10-20T00:00:00+08:00&to=2026-11-20T00:00:00+08:00&includeCancelled=false
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events (from,includeCancelled,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}]})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: https://sg.itsag3t6.com/api/engagement/events/eventId
  * Node Name: `https://sg.itsag3t6.com/api/engagement/events/eventId ()({title,description,location,startsAt,endsAt,clientId,reminderOffsetsMinutes:[{}],version})`
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


