package bank.engagement.notifications;

import org.springframework.web.util.HtmlUtils;

/** The HTML alternative of a plain-text message: escaped, one paragraph per blank line. */
public final class HtmlText {

    private HtmlText() {
    }

    public static String of(String text) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            html.append("<p>").append(HtmlUtils.htmlEscape(paragraph.strip()).replaceAll("\\R", "<br>"))
                    .append("</p>");
        }
        return html.append("</body></html>").toString();
    }
}
