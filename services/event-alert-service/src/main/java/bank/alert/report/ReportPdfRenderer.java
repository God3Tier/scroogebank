package bank.alert.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Renders the client report PDF on the server (news-alert-drafts plan, section 5.4), laid out like a financial
 * document: a cover page, a contents page, one section per company starting on a new page, and a closing page with
 * the disclaimer. Every page has a footer with the document date and the page number.
 *
 * <p>The PDF holds no client data. The same input always gives byte-for-byte the same file: the metadata and the
 * document ID are derived from the input, never from the time of rendering, and the standard fonts are not embedded.
 */
public final class ReportPdfRenderer {

    public static final String SINGAPORE = "Asia/Singapore";
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 56;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float TOP = PAGE.getHeight() - MARGIN;
    private static final float BOTTOM = MARGIN + 24;
    private static final int CONTENTS_PER_PAGE = 16;

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font ITALIC = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final float[] INK = {0.10f, 0.12f, 0.16f};
    private static final float[] MUTED = {0.38f, 0.42f, 0.48f};
    private static final float[] ACCENT = {0.05f, 0.29f, 0.45f};

    /** One source of a company's section, numbered from 1 within that section. */
    public record SourceLine(String id, String title, String domain, Instant publishedAt, String url) {
    }

    /** One company: its report text (schema alert-client-report-v2), sources and disclosure line. */
    public record Section(String issuer, String ticker, String disclosure, JsonNode report, List<SourceLine> sources) {
        public Section {
            sources = List.copyOf(sources);
        }

        String headline() {
            return report.path("headline").asText("");
        }
    }

    /** {@code preparedBy} is the agent's name, or null for "your relationship manager". */
    public record Document(LocalDate date, String preparedBy, List<Section> sections) {
        public Document {
            sections = List.copyOf(sections);
            if (sections.isEmpty()) throw new IllegalArgumentException("a report document needs at least one section");
        }
    }

    /** The PDF, and the page each section starts on (1 is the cover), in the order of the sections. */
    public record Rendered(byte[] pdf, List<Integer> startPages, int pageCount) {
    }

    public static String fileName(LocalDate date) {
        return "Portfolio-update-" + date + ".pdf";
    }

    public Rendered render(Document document) {
        List<List<Op>> sectionPages = new ArrayList<>();
        List<Integer> sectionPageCounts = new ArrayList<>();
        for (Section section : document.sections()) {
            List<List<Op>> pages = layoutSection(section);
            sectionPageCounts.add(pages.size());
            sectionPages.addAll(pages);
        }
        int contentsPages = (document.sections().size() + CONTENTS_PER_PAGE - 1) / CONTENTS_PER_PAGE;
        List<Integer> starts = new ArrayList<>();
        int next = 1 + contentsPages + 1;
        for (int count : sectionPageCounts) {
            starts.add(next);
            next += count;
        }

        List<List<Op>> pages = new ArrayList<>();
        pages.add(cover(document));
        pages.addAll(contents(document, starts));
        pages.addAll(sectionPages);
        pages.add(closing(document));
        return new Rendered(write(document, pages), List.copyOf(starts), pages.size());
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    /** One piece of text drawn at one place. */
    private record Op(PDType1Font font, float size, float x, float y, String text, float[] colour) {
    }

    /** Lays text out top to bottom, starting a new page when the current one is full. */
    private static final class Flow {
        final List<List<Op>> pages = new ArrayList<>();
        List<Op> page;
        float y;

        Flow() {
            newPage();
        }

        void newPage() {
            page = new ArrayList<>();
            pages.add(page);
            y = TOP;
        }

        void gap(float height) {
            y -= height;
        }

        void keep(float height) {
            if (y - height < BOTTOM) newPage();
        }

        void paragraph(String text, PDType1Font font, float size, float indent, float[] colour) {
            float leading = size * 1.4f;
            for (String line : wrap(clean(text), font, size, WIDTH - indent)) {
                keep(leading);
                y -= leading;
                page.add(new Op(font, size, MARGIN + indent, y, line, colour));
            }
        }

        void bullet(String text, PDType1Font font, float size) {
            float leading = size * 1.4f;
            List<String> lines = wrap(clean(text), font, size, WIDTH - 14);
            for (int i = 0; i < lines.size(); i++) {
                keep(leading);
                y -= leading;
                if (i == 0) page.add(new Op(font, size, MARGIN + 2, y, "•", INK));
                page.add(new Op(font, size, MARGIN + 14, y, lines.get(i), INK));
            }
        }

        void heading(String text) {
            keep(40);
            gap(12);
            paragraph(text, BOLD, 11.5f, 0, ACCENT);
            gap(2);
        }
    }

    private static List<List<Op>> layoutSection(Section section) {
        Flow flow = new Flow();
        JsonNode report = section.report();
        Map<String, Integer> numbers = new HashMap<>();
        for (int i = 0; i < section.sources().size(); i++) numbers.put(section.sources().get(i).id(), i + 1);

        String title = section.ticker() == null || section.ticker().isBlank()
                ? section.issuer() : section.issuer() + " (" + section.ticker() + ")";
        flow.paragraph(title, BOLD, 18, 0, INK);
        flow.gap(4);
        flow.paragraph(section.headline(), BOLD, 13, 0, INK);
        flow.gap(4);
        flow.paragraph(section.disclosure(), ITALIC, 9, 0, MUTED);

        flow.heading("Executive summary");
        for (JsonNode sentence : report.path("executiveSummary")) {
            flow.paragraph(sentence.path("text").asText() + cites(sentence, numbers), REGULAR, 10.5f, 0, INK);
            flow.gap(3);
        }
        if (report.path("keyFacts").size() > 0) {
            flow.heading("Key facts");
            for (JsonNode fact : report.path("keyFacts")) {
                flow.bullet(fact.path("label").asText() + ": " + fact.path("value").asText() + ". "
                        + fact.path("context").asText() + cites(fact, numbers), REGULAR, 10.5f);
            }
        }
        if (report.path("whyItMatters").size() > 0) {
            flow.heading("Why it matters");
            for (JsonNode item : report.path("whyItMatters")) {
                String kind = "POTENTIAL_IMPLICATION".equals(item.path("kind").asText()) ? " (potential implication)" : "";
                flow.bullet(item.path("text").asText() + kind + cites(item, numbers), REGULAR, 10.5f);
            }
        }
        if (report.path("whatToMonitor").size() > 0) {
            flow.heading("What to monitor");
            for (JsonNode item : report.path("whatToMonitor")) flow.bullet(item.asText(), REGULAR, 10.5f);
        }
        String uncertainty = report.path("uncertainty").asText("");
        if (!uncertainty.isBlank()) {
            flow.heading("Uncertainty");
            flow.paragraph(uncertainty, REGULAR, 10.5f, 0, INK);
        }
        if (!section.sources().isEmpty()) {
            flow.heading("Sources");
            for (int i = 0; i < section.sources().size(); i++) {
                SourceLine source = section.sources().get(i);
                String published = source.publishedAt() == null ? "date not supplied"
                        : SHORT_DATE.format(source.publishedAt().atZone(ZoneId.of(SINGAPORE)));
                flow.paragraph("[" + (i + 1) + "] " + source.title() + ". " + source.domain() + ", " + published + ".",
                        REGULAR, 9.5f, 0, INK);
                flow.paragraph(source.url(), REGULAR, 8.5f, 18, MUTED);
                flow.gap(3);
            }
        }
        return flow.pages;
    }

    private static String cites(JsonNode item, Map<String, Integer> numbers) {
        StringBuilder marks = new StringBuilder();
        item.path("sourceRefs").forEach(ref -> {
            Integer number = numbers.get(ref.asText());
            if (number != null) marks.append(" [").append(number).append(']');
        });
        return marks.toString();
    }

    private static List<Op> cover(Document document) {
        Flow flow = new Flow();
        flow.gap(170);
        flow.paragraph("PORTFOLIO INTELLIGENCE", BOLD, 10, 0, ACCENT);
        flow.gap(8);
        flow.paragraph("Portfolio update", BOLD, 30, 0, INK);
        flow.gap(10);
        flow.paragraph(LONG_DATE.format(document.date()), REGULAR, 13, 0, INK);
        flow.gap(30);
        flow.paragraph("Prepared by " + preparedBy(document) + ", your relationship manager", REGULAR, 11, 0, INK);
        int count = document.sections().size();
        flow.paragraph(count + (count == 1 ? " company" : " companies") + " in this update", REGULAR, 11, 0, MUTED);
        flow.gap(40);
        flow.paragraph("This document is for information only and is based on public sources. It is not investment "
                + "advice. Important information is on the last page.", ITALIC, 9.5f, 0, MUTED);
        return flow.page;
    }

    private static List<List<Op>> contents(Document document, List<Integer> starts) {
        List<List<Op>> pages = new ArrayList<>();
        for (int first = 0; first < document.sections().size(); first += CONTENTS_PER_PAGE) {
            Flow flow = new Flow();
            flow.paragraph(first == 0 ? "Contents" : "Contents (continued)", BOLD, 18, 0, INK);
            flow.gap(14);
            for (int i = first; i < Math.min(first + CONTENTS_PER_PAGE, document.sections().size()); i++) {
                Section section = document.sections().get(i);
                String page = "Page " + starts.get(i);
                float pageWidth = width(page, REGULAR, 10.5f);
                String name = fit(clean(section.issuer()), BOLD, 10.5f, WIDTH - pageWidth - 16);
                flow.y -= 15;
                flow.page.add(new Op(BOLD, 10.5f, MARGIN, flow.y, name, INK));
                flow.page.add(new Op(REGULAR, 10.5f, MARGIN + WIDTH - pageWidth, flow.y, page, INK));
                flow.y -= 13;
                flow.page.add(new Op(REGULAR, 9.5f, MARGIN, flow.y, fit(clean(section.headline()), REGULAR, 9.5f, WIDTH),
                        MUTED));
                flow.gap(8);
            }
            pages.add(flow.page);
        }
        return pages;
    }

    private static List<Op> closing(Document document) {
        Flow flow = new Flow();
        flow.paragraph("Important information", BOLD, 18, 0, INK);
        flow.gap(12);
        flow.paragraph(ReportText.DISCLAIMER, REGULAR, 10.5f, 0, INK);
        flow.gap(10);
        flow.paragraph("Each company section was drafted with AI from the public sources listed in it, then reviewed by "
                + "your relationship manager, " + preparedBy(document) + ", before it was sent. A section marked as "
                + "edited includes their changes.", REGULAR, 10.5f, 0, INK);
        flow.gap(10);
        flow.paragraph("If you have questions about this update, reply to the email it came with.", REGULAR, 10.5f, 0, INK);
        return flow.page;
    }

    private static String preparedBy(Document document) {
        return document.preparedBy() == null || document.preparedBy().isBlank()
                ? "your relationship manager" : clean(document.preparedBy());
    }

    // ---- writing ------------------------------------------------------------------------------------------------

    private static byte[] write(Document document, List<List<Op>> pages) {
        String footerLeft = "Portfolio update, " + LONG_DATE.format(document.date());
        try (PDDocument pdf = new PDDocument()) {
            for (int number = 1; number <= pages.size(); number++) {
                PDPage page = new PDPage(PAGE);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    for (Op op : pages.get(number - 1)) draw(content, op);
                    String right = "Page " + number + " of " + pages.size();
                    draw(content, new Op(REGULAR, 8.5f, MARGIN, MARGIN - 4, footerLeft, MUTED));
                    draw(content, new Op(REGULAR, 8.5f, MARGIN + WIDTH - width(right, REGULAR, 8.5f), MARGIN - 4, right, MUTED));
                }
            }
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(footerLeft);
            info.setProducer("Scrooge Bank CRM");
            Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone(SINGAPORE), Locale.ENGLISH);
            fixed.clear();
            fixed.set(document.date().getYear(), document.date().getMonthValue() - 1, document.date().getDayOfMonth());
            info.setCreationDate(fixed);
            info.setModificationDate(fixed);
            byte[] id = documentId(document, pages);
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            pdf.getDocument().getTrailer().setItem(COSName.ID, ids);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("the report PDF could not be written", failure);
        }
    }

    private static void draw(PDPageContentStream content, Op op) throws IOException {
        content.beginText();
        content.setFont(op.font(), op.size());
        content.setNonStrokingColor(op.colour()[0], op.colour()[1], op.colour()[2]);
        content.newLineAtOffset(op.x(), op.y());
        content.showText(op.text());
        content.endText();
    }

    /** The document ID comes from what is drawn, so the same input gives the same file. */
    private static byte[] documentId(Document document, List<List<Op>> pages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(document.date().toString().getBytes(StandardCharsets.UTF_8));
            for (List<Op> page : pages) {
                for (Op op : page) digest.update((op.text() + '|' + op.x() + '|' + op.y()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- text ---------------------------------------------------------------------------------------------------

    private static final Map<Character, String> REPLACEMENTS = Map.of(
            ' ', " ", ' ', " ", ' ', " ", '−', "-", '‐', "-", '‑', "-", '→', "->");

    /**
     * The standard fonts cover Western European text only (WinAnsi). Anything else is replaced, so a character a
     * source happens to contain cannot stop the PDF being made.
     */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                out.append(' ');
            } else if (cp < 0x20 || (cp >= 0x7f && cp < 0xa0)) {
                // control characters are dropped
            } else if (cp <= 0xffff && REPLACEMENTS.containsKey((char) cp)) {
                out.append(REPLACEMENTS.get((char) cp));
            } else if (encodable(cp)) {
                out.appendCodePoint(cp);
            } else {
                out.append('?');
            }
        });
        return out.toString().replaceAll(" {2,}", " ").strip();
    }

    private static final Map<Integer, Boolean> ENCODABLE = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean encodable(int codePoint) {
        return ENCODABLE.computeIfAbsent(codePoint, cp -> {
            try {
                REGULAR.encode(new String(Character.toChars(cp)));
                BOLD.encode(new String(Character.toChars(cp)));
                return true;
            } catch (IOException | IllegalArgumentException unsupported) {
                return false;
            }
        });
    }

    private static float width(String text, PDType1Font font, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException | IllegalArgumentException e) {
            return text.length() * size * 0.5f;
        }
    }

    static List<String> wrap(String text, PDType1Font font, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, size) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) lines.add(line.toString());
            line.setLength(0);
            // A word wider than the line (a long URL) is broken where it must be.
            String rest = word;
            while (width(rest, font, size) > maxWidth) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(rest.substring(0, cut), font, size) > maxWidth) cut--;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String fit(String text, PDType1Font font, float size, float maxWidth) {
        if (width(text, font, size) <= maxWidth) return text;
        String shortened = text;
        while (shortened.length() > 1 && width(shortened + "...", font, size) > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened.strip() + "...";
    }

    /** The Singapore calendar date of an instant: the document date. */
    public static LocalDate singaporeDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneId.of(SINGAPORE));
    }
}
