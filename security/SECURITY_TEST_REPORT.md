# Security Testing Report

## Client Profile Management System

**Testing Tools:** OWASP ZAP, Burp Suite Community Edition  
**Application Stack:** React, Go (Gin), AWS Cognito, AWS Cloud Infrastructure  
**Testing Type:** Web Application and REST API Security Testing  
**Environment:** AWS-hosted application (decommissioned)  
**Status:** Testing completed; further testing unavailable following project submission and deployment termination.

---

## 1. Overview

This report documents the security testing conducted on the Client Profile Management System to evaluate authentication, role-based access control (RBAC), API authorization, privilege escalation restrictions, and potential SQL injection vulnerabilities.

Testing was performed using OWASP ZAP for automated vulnerability scanning and Burp Suite Repeater for manual HTTP request manipulation and authorization testing.

The application uses AWS Cognito for authentication and supports multiple user roles, including Root Admin, Admin, and Agent.

### Testing Objectives

- Assess the application for potential SQL injection vulnerabilities.
- Verify that role-based access controls are enforced.
- Test whether restricted REST API endpoints can be accessed by unauthorized roles.
- Evaluate privilege escalation restrictions between administrative roles.
- Identify unauthorized access to frontend routes and potential backend authorization weaknesses.

---

## 2. Security Testing Summary

| ID | Test / Finding | Result | Status | Contributor(s) |
|---|---|---|---|---|
| SEC-001 | Potential Broken Function Level Authorization | Unauthorized frontend route access identified | Frontend mitigated; backend unverified | Yong Huey |
| SEC-002 | Admin Creation Privilege Escalation | HTTP 403 returned for unauthorized admin creation | Passed | Yong Huey |
| SEC-003 | Agent Access to Administrative Endpoints | HTTP 403 returned for Agent accessing Admin-only endpoints | Passed | Yong Huey |
| SEC-004 | SQL Injection Vulnerability Assessment | No SQL injection alerts reported by OWASP ZAP | No findings detected within scan coverage | Yong Huey |

---

## 3. Detailed Security Findings

### SEC-001: Potential Broken Function Level Authorization

**Category:** OWASP API5:2023 – Broken Function Level Authorization  
**Status:** Unverified backend vulnerability / Frontend mitigation implemented

#### Description

During manual security testing, an administrator was able to access an agent-specific client transaction page by directly modifying the frontend URL.

Although administrators were authorized to view aggregate transaction information, access to the agent-specific transaction interface was intended to be restricted to agents.

#### Observed Behaviour

- Direct navigation to `/agent/client/<client_id>/transactions` succeeded using an administrator account.
- The page successfully retrieved and displayed transaction data.
- A role-based authorization check was subsequently implemented in the React router.

#### Potential Impact

If the corresponding backend API does not independently validate role permissions, an administrator may be able to bypass frontend route restrictions and retrieve individual client transaction data by sending direct API requests using tools such as Burp Suite Repeater.

#### Remediation Implemented

Added frontend route authorization checks to restrict access to users possessing the agent role.

#### Remaining Risk

Backend authorization for individual transaction retrieval was not independently verified.

#### Limitations

Further testing was not possible following project submission, code freeze, and termination of the AWS deployment.

#### Recommended Remediation

Implement and verify server-side role-based authorization for individual transaction endpoints, independently of frontend route restrictions.

---

### SEC-002: Admin Creation Privilege Escalation Testing

**Category:** OWASP API5:2023 – Broken Function Level Authorization  
**Tool:** Burp Suite Repeater  
**Status:** Passed – Authorization restriction enforced

#### Description

The application implements different administrative privileges.

Only users possessing the Root Admin role are permitted to create additional administrator accounts.

This test evaluated whether a regular Admin could bypass the restriction by manually submitting an administrator creation request to the backend API.

#### Testing Methodology

1. Authenticated into the application using a regular Admin account.
2. Used Burp Suite to inspect and construct the HTTP request associated with administrator creation.
3. Submitted the request through Burp Suite Repeater using the regular Admin's authentication credentials.
4. Attempted to create another administrator account by submitting the relevant JSON payload.
5. Examined the HTTP response to determine whether the backend enforced the required privilege restrictions.

#### Expected Behaviour

The backend should reject attempts by regular Admin accounts to create additional administrators.

**Expected HTTP Response:** `403 Forbidden`

#### Observed Behaviour

The backend returned:

`403 Forbidden`

The administrator creation request was rejected as expected.

#### Security Assessment

The tested endpoint correctly enforced the restriction preventing a regular Admin from creating another administrator.

This demonstrated that the authorization control was implemented at the API level rather than relying solely on frontend restrictions.

#### Conclusion

No privilege escalation vulnerability was identified in the tested scenario.

The restriction was successfully validated through manual request manipulation using Burp Suite Repeater.

---

### SEC-003: Agent Access to Administrative API Endpoints

**Category:** OWASP API5:2023 – Broken Function Level Authorization  
**Tool:** Burp Suite Repeater  
**Status:** Passed – Role-based authorization enforced

#### Description

The application implements role-based access controls (RBAC) to separate Agent and Admin functionalities.

Administrative API endpoints are intended to be accessible only to users with the required administrative privileges.

This assessment evaluated whether an authenticated Agent could bypass frontend restrictions and directly invoke administrative REST API endpoints using Burp Suite Repeater.

#### Testing Methodology

1. Authenticated into the application using an Agent account.
2. Identified administrative API requests using Burp Suite.
3. Submitted requests to restricted administrative endpoints through Burp Suite Repeater.
4. Used the Agent's valid authentication credentials when sending the requests.
5. Examined the HTTP responses to determine whether the backend correctly enforced role-based authorization.

#### Expected Behaviour

The application distinguishes between unauthenticated requests and authenticated requests lacking sufficient permissions.

| Scenario | Expected HTTP Response |
|---|---|
| Request without valid authentication | `401 Unauthorized` |
| Authenticated Agent accessing Admin-only endpoint | `403 Forbidden` |
| Authenticated Admin accessing permitted Admin endpoint | Request permitted, subject to validation |

For the tested scenario, the expected response was `403 Forbidden`, as the Agent was authenticated but lacked the required administrative permissions.

#### Observed Behaviour

The backend returned:

`403 Forbidden`

Agent requests to the tested administrative endpoints were rejected as expected.

#### Security Assessment

The tested endpoints correctly enforced role-based authorization at the backend API level.

Despite directly invoking restricted endpoints through Burp Suite Repeater, the authenticated Agent was unable to bypass the administrative role restrictions.

This demonstrated that the tested access restrictions were enforced by the backend rather than relying solely on frontend route visibility or navigation controls.

#### Conclusion

No Broken Function Level Authorization vulnerability was identified in the tested Agent-to-Admin endpoint scenarios.

The backend successfully returned the expected `403 Forbidden` response for authenticated users lacking the required administrative privileges.

---

### SEC-004: SQL Injection Vulnerability Assessment

**Category:** CWE-89 – SQL Injection  
**Tool:** OWASP ZAP  
**Status:** No SQL injection findings reported

#### Description

An automated security assessment was conducted using OWASP ZAP to identify potential SQL injection vulnerabilities within the deployed application.

The application uses AWS Cognito for authentication rather than implementing its own SQL-backed login functionality.

However, SQL injection risks may still exist in backend API endpoints that interact with a database.

#### Testing Methodology

1. Configured OWASP ZAP to assess the deployed web application.
2. Executed a vulnerability scan targeting potential SQL injection weaknesses.
3. Reviewed the scan results for SQL injection alerts.
4. Evaluated the findings reported by OWASP ZAP.

#### Expected Behaviour

Application endpoints should safely handle untrusted inputs without allowing user-controlled data to modify the intended SQL query structure.

#### Observed Behaviour

OWASP ZAP did not report any SQL injection vulnerabilities during the assessment.

#### Security Assessment

No SQL injection findings were detected within the scope of the completed scan.

However, the absence of automated findings does not guarantee that the application is free from SQL injection vulnerabilities.

Scan coverage of authenticated API endpoints, URL parameters, and backend database operations was not independently verified.

AWS Cognito and MFA provide authentication protections but do not directly prevent SQL injection in application database queries.

#### Recommended Improvements

- Use parameterized SQL queries or prepared statements throughout the Go backend.
- Validate untrusted inputs received through API requests.
- Review database queries for unsafe string concatenation.
- Include authenticated API endpoints in future security assessments.
- Supplement automated scanning with source code review.

#### Conclusion

No SQL injection vulnerabilities were reported by OWASP ZAP during the assessment.

Further manual and source-level verification would be required to establish broader coverage.

---

## 4. Tools and Techniques

| Tool | Purpose |
|---|---|
| OWASP ZAP | Automated vulnerability assessment for potential SQL injection |
| Burp Suite Proxy | Inspection of HTTP requests and responses |
| Burp Suite Repeater | Manual manipulation and replay of API requests |
| Browser Developer Tools | Inspection and testing of frontend route behavior |

### Security Testing Techniques

- Role-Based Access Control (RBAC) validation
- Broken Function Level Authorization testing
- Privilege escalation testing
- HTTP request and JSON payload manipulation
- Frontend route authorization testing
- Automated vulnerability scanning

---

## 5. Limitations

The security assessment was conducted during the development and deployment stages of the project.

The following limitations apply:

1. Automated vulnerability scanning could not guarantee complete coverage of all backend API endpoints.
2. Testing results apply only to the endpoints and role combinations assessed.
3. Backend authorization for individual transaction retrieval was not independently verified after the frontend fix.
4. Application source code was frozen following project submission.
5. The AWS deployment was subsequently terminated, preventing additional testing and verification.

As a result, this report documents the tests performed and their observed outcomes but does not constitute a comprehensive penetration test or security certification.

---

## 6. Conclusion

Security testing of the Client Profile Management System involved both automated vulnerability scanning and manual API authorization testing.

The assessment produced the following outcomes:

- No SQL injection vulnerabilities were reported by OWASP ZAP within the tested scope.
- Backend authorization correctly rejected an unauthorized administrator creation attempt with HTTP `403 Forbidden`.
- Backend RBAC controls successfully prevented authenticated Agent accounts from accessing the tested Admin-only API endpoints, returning HTTP `403 Forbidden`.
- A frontend route-access control issue was identified that allowed an Admin to access an Agent-specific transaction page.
- The frontend issue was mitigated by introducing role-based route checks.
- A potential backend authorization risk was documented for future verification.

This assessment demonstrated the importance of enforcing authorization at both the frontend navigation layer and the backend API layer.

While frontend role checks improve navigation security, backend authorization remains essential because direct API requests can bypass client-side restrictions.

Future security assessments should prioritize comprehensive API authorization testing, source code review, and regression testing following remediation.

---

## 7. References

- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
- [OWASP Broken Access Control](https://owasp.org/Top10/A01_2021-Broken_Access_Control/)
- [OWASP SQL Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html)
- [OWASP ZAP Documentation](https://www.zaproxy.org/docs/)
- [PortSwigger Burp Suite Documentation](https://portswigger.net/burp/documentation)
