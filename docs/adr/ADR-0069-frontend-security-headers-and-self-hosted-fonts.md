# ADR-0069: Security headers at CloudFront and self-hosted fonts

- Status: Proposed (2026-11-02)
- Decision date/time: 2026-11-02 14:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (to accept); the frontend's owners should review the Content-Security-Policy
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The OWASP ZAP baseline scan of the deployed app on 2026-11-02 ([report](../testing/load-and-zap-test-2026-11-02.md)) raised two Medium alerts and several Low ones, all about the frontend's response headers:

- **No Content-Security-Policy.** CloudFront applied AWS's `Managed-SecurityHeadersPolicy`, which sets HSTS, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy` and `X-XSS-Protection`, but no CSP. Nothing limited where the page could load scripts from or send data to if an injection got in.
- **No Subresource Integrity on the Google Fonts stylesheet.** Google's stylesheet differs by browser, so it cannot carry a fixed hash. Every page load also told Google each user's IP address and the app they were using.
- **Low:** no `Permissions-Policy`, no cross-origin opener or resource policies, and S3's `Server: AmazonS3` header named the origin.

The API responses through CloudFront's `/api/*` behaviour had the same managed policy and no cross-origin resource policy.

## Decision Drivers

- A CSP that blocks injected scripts and data exfiltration, without breaking sign-in (Cognito managed login with PKCE, ADR-0025) or the identity-document preview (ADR-0041).
- No third party sees which bank staff use the CRM, or when.
- Headers set in one place for every response, as they are today.

## Options Considered

### Option A: our own CloudFront response-headers policies, and fonts bundled with the app (chosen)

- Pros: one change at the edge covers every response; the CSP can allow only the app's origin and Cognito; no third-party requests.
- Cons: the icon font adds about 750 KB to the first load (cached afterwards); two policies to keep in step with the frontend.

### Option B: a CSP `<meta>` tag in `index.html`

- Pros: lives with the frontend code.
- Cons: cannot set `frame-ancestors` or the other headers, and does not cover the API.

### Option C: keep Google Fonts and allow it in the CSP

- Pros: smallest change.
- Cons: the Subresource Integrity gap and the third-party requests stay.

## Decision

1. **The frontend gets its own CloudFront response-headers policy** (`stacks/crm/edge.tf`), replacing the managed one. It keeps everything the managed policy set, and adds:
   - A Content-Security-Policy that allows scripts, styles and fonts from the app's origin only. Connections and frames are allowed to the app's origin and the two Cognito hosts (the user pool's issuer and its managed-login domain). Images and frames may also be `blob:`, for the document preview. `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'self'` and `upgrade-insecure-requests` complete it.
   - A `Permissions-Policy` that turns off the camera, microphone, location, payment, USB and motion sensors.
   - `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Resource-Policy: same-origin`.
   - The `Server` header is removed.
2. **The API gets a stricter policy**: `default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, the same permissions and resource policies, and no `Server` header. The API returns data, never pages.
3. **No Cross-Origin-Embedder-Policy.** The app needs no cross-origin isolation, and `require-corp` can block the browser's PDF viewer in the document preview. ZAP's Low alert for it is accepted for this reason.
4. **Fonts are bundled with the app.** Inter and Material Symbols Outlined come from the `@fontsource-variable` packages, pinned exactly (5.3.0 and 5.3.9), and are served from the app's own origin. `index.html` no longer links Google Fonts.

## Rationale

Option A closes both Medium alerts and all but one Low alert with no change to the services. Bundling the fonts is the only way to give them integrity protection, and it also removes the only third-party origin the app loaded.

## Consequences

### Positive

- An injected script cannot load code from or send data to any origin outside the app and Cognito.
- No third-party request on any page.
- The API's responses cannot be framed or rendered as pages.

### Negative / Trade-offs

- A new external origin the frontend needs (a CDN, an analytics tool, a new Cognito domain) must be added to the CSP first, or the browser blocks it.
- The first visit downloads about 750 KB more for the icon font.
- A CSP violation shows only in the browser console; there is no reporting endpoint.

## Security & Privacy Impact

Positive. The CSP limits what an injection could do, and users' IP addresses no longer go to Google. Nothing is logged that was not before.

## Cost Impact

None: response-headers policies have no charge, and the fonts are served from the existing bucket through CloudFront.

## Operational Impact

When the frontend adds an external origin, update `frontend_csp` in `stacks/crm/edge.tf` in the same pull request. The deployed headers can be checked with `curl -sI https://<app host>/`.

## Validation

- After redeploying, the ZAP baseline and API scans no longer report the CSP, Subresource Integrity, `Permissions-Policy`, opener or resource policy, or `Server` alerts. The follow-up report [load-and-zap-retest-2026-11-02.md](../testing/load-and-zap-retest-2026-11-02.md) gives the results.
- Sign-in, sign-out, token refresh, the dashboards and the document preview work in a browser with the policy applied, with no CSP violations in the console.
- The frontend's tests pass, and the build contains no Google Fonts reference.

## References

- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)
- OWASP ZAP alerts 10038, 90003, 10063, 90004, 10036
