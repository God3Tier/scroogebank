package bank.dashboard.dto;

/**
 * How the figures in a response were scoped.
 *
 * <p>Always derived from the caller's token, never from a request parameter.
 * That is the whole of the dashboard's scoping story: it passes no agent
 * identifier upstream, so an agent has nothing to forge, and the service that
 * owns the client records decides what the caller may see.
 */
public enum Scope {
    ADMIN,
    AGENT
}
