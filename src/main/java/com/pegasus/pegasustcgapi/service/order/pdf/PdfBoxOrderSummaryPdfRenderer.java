package com.pegasus.pegasustcgapi.service.order.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

/**
 * Produces the shared, printable Order Summary document used by Buyer, Seller, and Admin views.
 *
 * <p>The renderer deliberately accepts only {@link OrderSummaryPdfDocument}. Authorization and
 * mapping from role-specific order projections belong to the API layer that invokes it.
 */
@Service
public class PdfBoxOrderSummaryPdfRenderer implements OrderSummaryPdfRenderer {

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float PAGE_WIDTH = PAGE_SIZE.getWidth();
    private static final float PAGE_HEIGHT = PAGE_SIZE.getHeight();
    private static final float MARGIN = 38f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2);
    private static final float FOOTER_Y = 34f;
    private static final float BOTTOM_LIMIT = 62f;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.ENGLISH);

    private static final Color PRIMARY = new Color(0, 105, 168);
    private static final Color INK = new Color(9, 11, 12);
    private static final Color MUTED = new Color(103, 120, 124);
    private static final Color PANEL = new Color(245, 249, 252);
    private static final Color BORDER = new Color(227, 231, 232);

    @Override
    public RenderedPdf render(OrderSummaryPdfDocument document) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Fonts fonts = Fonts.load(pdf);
            Canvas canvas = new Canvas(pdf, fonts, document);
            canvas.openPage(true);

            drawOrderOverview(canvas, document);
            drawBuyer(canvas, document.buyer());
            drawSellerSections(canvas, document);
            drawShipping(canvas, document.shipping());
            if (document.role() != OrderSummaryPdfRole.BUYER) {
                drawPayment(canvas, document);
            }
            drawTotals(canvas, document);
            drawStatusHistory(canvas, document);

            canvas.close();
            drawFooters(pdf, fonts);
            pdf.save(output);
            return new RenderedPdf(fileName(document.orderNumber()), output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not render order summary PDF", exception);
        }
    }

    private void drawOrderOverview(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        canvas.line(canvas.y() + 2f, PRIMARY, 3f);
        canvas.move(25f);
        canvas.text(document.orderNumber(), MARGIN, canvas.y(), 24f, canvas.fonts.bold, INK);
        String activity = "Placed " + formatDate(document.placedAt());
        if (document.paidAt() != null) {
            activity += " · Payment confirmed";
        }
        canvas.text(activity, MARGIN, canvas.y() - 16f, 9f, canvas.fonts.regular, MUTED);
        canvas.badge(document.status(), PAGE_WIDTH - MARGIN - 86f, canvas.y() - 4f);
        canvas.move(42f);
    }

    private void drawBuyer(Canvas canvas, OrderSummaryPdfDocument.Party buyer) throws IOException {
        canvas.ensure(86f);
        float cardWidth = (CONTENT_WIDTH - 16f) / 2f;
        canvas.partyCard(MARGIN, cardWidth, "BUYER", buyer == null ? "Not available" : buyer.displayName(),
                buyer == null ? null : buyer.username(), null);
        String sellerName = "Seller unavailable";
        String sellerOrder = null;
        if (!canvas.document.sellerSections().isEmpty()) {
            OrderSummaryPdfDocument.SellerSection seller = canvas.document.sellerSections().getFirst();
            sellerName = seller.sellerName();
            sellerOrder = seller.sellerOrderNumber();
        }
        canvas.partyCard(MARGIN + cardWidth + 16f, cardWidth, "SELLER", sellerName,
                sellerOrder == null ? null : "Store order #" + sellerOrder,
                canvas.document.sellerSections().size() > 1 ? "+" + (canvas.document.sellerSections().size() - 1) + " additional seller(s)" : null);
        canvas.move(98f);
    }

    private void drawSellerSections(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        for (OrderSummaryPdfDocument.SellerSection seller : document.sellerSections()) {
            canvas.ensure(58f);
            canvas.section("SELLER  " + seller.sellerName());
            if (seller.sellerOrderNumber() != null) {
                canvas.text("Sub-order: " + seller.sellerOrderNumber(), MARGIN, canvas.y() + 2f, 8.5f, canvas.fonts.regular, MUTED);
            }
            drawItems(canvas, seller.items(), document.currency());
        }
    }

    private void drawItems(Canvas canvas, List<OrderSummaryPdfDocument.LineItem> items, String currency) throws IOException {
        drawItemHeader(canvas);
        if (items.isEmpty()) {
            canvas.ensure(31f);
            canvas.text("No order items were available.", MARGIN + 8f, canvas.y() - 19f, 9f, canvas.fonts.regular, MUTED);
            canvas.line(canvas.y() - 30f);
            canvas.move(38f);
            return;
        }
        for (OrderSummaryPdfDocument.LineItem item : items) {
            List<String> productLines = wrap(itemDescription(item), 228f, canvas.fonts.regular, 8.5f);
            float rowHeight = Math.max(31f, (productLines.size() * 10.5f) + 12f);
            if (!canvas.fits(rowHeight)) {
                canvas.openPage(false);
                drawItemHeader(canvas);
            }
            float baseline = canvas.y() - 14f;
            for (String line : productLines) {
                canvas.text(line, MARGIN + 6f, baseline, 8.5f, canvas.fonts.regular, INK);
                baseline -= 10.5f;
            }
            canvas.text(String.valueOf(item.quantity()), MARGIN + 248f, canvas.y() - 15f, 8.5f, canvas.fonts.regular, INK);
            canvas.right(money(item.unitPrice(), currency), MARGIN + 360f, canvas.y() - 15f, 8.5f, canvas.fonts.regular, INK);
            canvas.right("-" + money(item.discount(), currency), MARGIN + 443f, canvas.y() - 15f, 8.5f, canvas.fonts.regular, INK);
            canvas.right(money(item.lineTotal(), currency), MARGIN + CONTENT_WIDTH - 6f, canvas.y() - 15f, 8.5f, canvas.fonts.bold, INK);
            canvas.line(canvas.y() - rowHeight);
            canvas.move(rowHeight);
        }
        canvas.move(8f);
    }

    private void drawItemHeader(Canvas canvas) throws IOException {
        canvas.ensure(28f);
        float y = canvas.y();
        canvas.text("PRODUCT", MARGIN + 2f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.text("QTY", MARGIN + 248f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.right("UNIT PRICE", MARGIN + 360f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.right("DISCOUNT", MARGIN + 443f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.right("TOTAL", MARGIN + CONTENT_WIDTH - 2f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.line(y - 20f);
        canvas.move(22f);
    }

    private void drawShipping(Canvas canvas, OrderSummaryPdfDocument.Shipping shipping) throws IOException {
        if (shipping == null || !shipping.hasAnyValue()) {
            return;
        }
        List<String> addressLines = shipping.address() == null
                ? List.of()
                : wrap(shipping.address(), CONTENT_WIDTH - 24f, canvas.fonts.regular, 8.5f);
        float height = 49f + (addressLines.size() * 10.5f);
        canvas.ensure(height + 12f);
        canvas.panel(height);
        float y = canvas.y() - 15f;
        canvas.text("DELIVERY OVERVIEW", MARGIN + 12f, y, 9f, canvas.fonts.bold, INK);
        String recipient = shipping.recipientName() == null ? "Recipient unavailable" : shipping.recipientName();
        canvas.text(recipient, MARGIN + 12f, y - 13f, 8.5f, canvas.fonts.regular, MUTED);
        if (shipping.address() != null) {
            float lineY = y - 25f;
            for (String line : addressLines) {
                canvas.text(line, MARGIN + 12f, lineY, 8.5f, canvas.fonts.regular, MUTED);
                lineY -= 10.5f;
            }
        }
        List<String> delivery = new ArrayList<>();
        if (shipping.carrierName() != null) {
            delivery.add("Carrier: " + shipping.carrierName());
        }
        if (shipping.trackingNumber() != null) {
            delivery.add("Tracking: " + shipping.trackingNumber());
        }
        if (shipping.estimatedDelivery() != null) {
            delivery.add("Estimated delivery: " + shipping.estimatedDelivery());
        }
        if (!delivery.isEmpty()) {
            canvas.text(String.join(" · ", delivery), MARGIN + 12f, canvas.y() - height + 12f, 8.5f, canvas.fonts.regular, MUTED);
        }
        canvas.move(height + 14f);
    }

    private void drawPayment(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        OrderSummaryPdfDocument.Payment payment = document.payment();
        if (payment == null || !payment.hasAnyValue()) {
            return;
        }
        canvas.ensure(64f);
        canvas.section("PAYMENT");
        canvas.panel(45f);
        float y = canvas.y() - 17f;
        canvas.label("METHOD", MARGIN + 12f, y);
        canvas.label("STATUS", MARGIN + 220f, y);
        canvas.label("AMOUNT", MARGIN + 360f, y);
        canvas.text(valueOrUnavailable(payment.method()), MARGIN + 12f, y - 15f, 9f, canvas.fonts.regular, INK);
        canvas.text(valueOrUnavailable(payment.status()), MARGIN + 220f, y - 15f, 9f, canvas.fonts.regular, INK);
        canvas.right(money(payment.amount(), document.currency()), MARGIN + CONTENT_WIDTH - 12f, y - 15f, 9f, canvas.fonts.bold, INK);
        canvas.move(57f);
    }

    private void drawTotals(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        OrderSummaryPdfDocument.Totals totals = document.totals();
        boolean includesSettlement = document.role() != OrderSummaryPdfRole.BUYER;
        boolean compactHistory = hasCompactStatusHistory(document);
        float height = includesSettlement ? 112f : 78f;
        canvas.ensure(height + 25f);
        canvas.section(includesSettlement ? "ORDER AND SETTLEMENT TOTALS" : "ORDER TOTALS");
        float panelTop = canvas.y();
        drawDocumentNote(canvas, height, compactHistory);
        float x = MARGIN + 280f;
        canvas.panelAt(x, canvas.y(), CONTENT_WIDTH - 280f, height);
        float row = canvas.y() - 17f;
        drawTotalRow(canvas, "Items subtotal", totals.itemsSubtotal(), document.currency(), x, row, false);
        row -= 16f;
        drawTotalRow(canvas, "Shipping", totals.shippingFee(), document.currency(), x, row, false);
        row -= 16f;
        drawTotalRow(canvas, "Discount", totals.discountTotal().negate(), document.currency(), x, row, false);
        row -= 19f;
        drawTotalRow(canvas, "Total paid", totals.totalPaid(), document.currency(), x, row, true);
        if (includesSettlement) {
            row -= 19f;
            drawTotalRow(canvas, "Platform commission", totals.commissionAmount().negate(), document.currency(), x, row, false);
            row -= 16f;
            drawTotalRow(canvas, "Seller net amount", totals.sellerNetAmount(), document.currency(), x, row, true);
        }
        if (compactHistory) {
            drawCompactStatusHistory(canvas, document.statusHistory(), panelTop);
        }
        canvas.move(height + 12f);
    }

    private void drawStatusHistory(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        if (document.role() != OrderSummaryPdfRole.ADMIN || document.statusHistory().isEmpty() || hasCompactStatusHistory(document)) {
            return;
        }
        canvas.ensure(48f);
        canvas.section("STATUS HISTORY");
        for (OrderSummaryPdfDocument.StatusEvent event : document.statusHistory()) {
            List<String> notes = event.note() == null ? List.of() : wrap(event.note(), CONTENT_WIDTH - 145f, canvas.fonts.regular, 8.5f);
            float height = Math.max(30f, 18f + (notes.size() * 10.5f));
            if (!canvas.fits(height)) {
                canvas.openPage(false);
                canvas.section("STATUS HISTORY (CONTINUED)");
            }
            canvas.line(canvas.y());
            canvas.text(formatDate(event.occurredAt()), MARGIN + 6f, canvas.y() - 14f, 8f, canvas.fonts.regular, MUTED);
            canvas.text(event.status(), MARGIN + 136f, canvas.y() - 14f, 8.5f, canvas.fonts.bold, INK);
            float noteY = canvas.y() - 14f;
            for (String note : notes) {
                canvas.text(note, MARGIN + 230f, noteY, 8.5f, canvas.fonts.regular, INK);
                noteY -= 10.5f;
            }
            canvas.move(height);
        }
        canvas.move(8f);
    }

    private void drawDocumentNote(Canvas canvas, float availableHeight, boolean compactHistory) throws IOException {
        float noteHeight = compactHistory ? 50f : Math.min(57f, availableHeight);
        canvas.panelAt(MARGIN, canvas.y(), 260f, noteHeight);
        canvas.label("DOCUMENT NOTE", MARGIN + 12f, canvas.y() - 16f);
        canvas.text("Generated from Pegasus TCG order data.", MARGIN + 12f, canvas.y() - 32f, 8f, canvas.fonts.regular, MUTED);
        canvas.text("Not a tax invoice or receipt.", MARGIN + 12f, canvas.y() - 44f, 8f, canvas.fonts.regular, MUTED);
    }

    private void drawCompactStatusHistory(
            Canvas canvas, List<OrderSummaryPdfDocument.StatusEvent> history, float panelTop) throws IOException {
        float y = panelTop - 63f;
        canvas.label("STATUS HISTORY", MARGIN + 12f, y);
        for (OrderSummaryPdfDocument.StatusEvent event : history) {
            y -= 13f;
            canvas.text(formatDate(event.occurredAt()), MARGIN + 12f, y, 7.5f, canvas.fonts.regular, MUTED);
            canvas.text(event.status(), MARGIN + 140f, y, 7.5f, canvas.fonts.bold, INK);
        }
    }

    private boolean hasCompactStatusHistory(OrderSummaryPdfDocument document) {
        return document.role() == OrderSummaryPdfRole.ADMIN
                && !document.statusHistory().isEmpty()
                && document.statusHistory().size() <= 2;
    }

    private void drawTotalRow(Canvas canvas, String label, BigDecimal value, String currency, float x, float y, boolean emphasize)
            throws IOException {
        FontFace font = emphasize ? canvas.fonts.bold : canvas.fonts.regular;
        float size = emphasize ? 9.5f : 8.5f;
        canvas.text(label, x + 12f, y, size, font, emphasize ? INK : MUTED);
        canvas.right(money(value, currency), MARGIN + CONTENT_WIDTH - 12f, y, size, font, INK);
    }

    private void drawFooters(PDDocument pdf, Fonts fonts) throws IOException {
        int pageCount = pdf.getNumberOfPages();
        for (int index = 0; index < pageCount; index++) {
            PDPage page = pdf.getPage(index);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                text(content, "PEGASUS TCG · support@pegasustcg.com", MARGIN, FOOTER_Y, 8.5f, fonts.regular, MUTED);
                right(content, "Page " + (index + 1) + " of " + pageCount, PAGE_WIDTH - MARGIN, FOOTER_Y, 8.5f, fonts.regular, MUTED);
            }
        }
    }

    private static String itemDescription(OrderSummaryPdfDocument.LineItem item) {
        List<String> details = new ArrayList<>();
        if (item.variantLabel() != null) details.add(item.variantLabel());
        if (item.condition() != null) details.add(item.condition());
        if (item.gameName() != null) details.add(item.gameName());
        return details.isEmpty() ? item.productName() : item.productName() + " · " + String.join(" · ", details);
    }

    private static String money(BigDecimal value, String currency) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return String.format(Locale.US, "%s %,.2f", currency, amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatDate(OffsetDateTime value) {
        return value == null ? "Not available" : DATE_TIME.format(value);
    }

    private static String valueOrUnavailable(String value) {
        return value == null ? "Not available" : value;
    }

    private static String roleCopy(OrderSummaryPdfDocument document) {
        String copy = switch (document.role()) {
            case BUYER -> "Buyer copy";
            case SELLER -> "Seller copy";
            case ADMIN -> "Internal admin copy";
        };
        return copy + " · Order #" + document.orderNumber() + " · " + formatDate(document.generatedAt());
    }

    private static String fileName(String orderNumber) {
        String safeOrder = orderNumber.replaceAll("[^A-Za-z0-9._-]", "_");
        return "order-summary-" + safeOrder + ".pdf";
    }

    private static List<String> wrap(String value, float width, FontFace font, float size) throws IOException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (line.isEmpty()) {
                appendBrokenWord(lines, line, word, width, font, size);
            } else if (textWidth(font, size, line + " " + word) <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                appendBrokenWord(lines, line, word, width, font, size);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void appendBrokenWord(List<String> lines, StringBuilder line, String word, float width, FontFace font, float size)
            throws IOException {
        for (int offset = 0; offset < word.length();) {
            int codePoint = word.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (!line.isEmpty() && textWidth(font, size, line + character) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(character);
            offset += Character.charCount(codePoint);
        }
    }

    private static float textWidth(FontFace font, float size, String value) throws IOException {
        float width = 0f;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            width += font.forCodePoint(codePoint).getStringWidth(character) / 1000f * size;
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static void text(PDPageContentStream content, String value, float x, float y, float size, FontFace font, Color color)
            throws IOException {
        content.beginText();
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        PDFont current = null;
        StringBuilder run = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            PDFont next = font.forCodePoint(codePoint);
            if (current != null && current != next) {
                content.setFont(current, size);
                content.showText(run.toString());
                run.setLength(0);
            }
            current = next;
            run.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        if (!run.isEmpty()) {
            content.setFont(current, size);
            content.showText(run.toString());
        }
        content.endText();
    }

    private static void right(PDPageContentStream content, String value, float x, float y, float size, FontFace font, Color color)
            throws IOException {
        text(content, value, x - textWidth(font, size, value), y, size, font, color);
    }

    private static final class Fonts {
        private final FontFace regular;
        private final FontFace bold;

        private Fonts(FontFace regular, FontFace bold) {
            this.regular = regular;
            this.bold = bold;
        }

        private static Fonts load(PDDocument document) throws IOException {
            try (InputStream regular = requiredFont("/fonts/NotoSans-Regular.ttf");
                    InputStream bold = requiredFont("/fonts/NotoSans-Bold.ttf");
                    InputStream thaiRegular = requiredFont("/fonts/NotoSansThai-Regular.ttf");
                    InputStream thaiBold = requiredFont("/fonts/NotoSansThai-Bold.ttf")) {
                return new Fonts(
                        new FontFace(PDType0Font.load(document, regular, true), PDType0Font.load(document, thaiRegular, true)),
                        new FontFace(PDType0Font.load(document, bold, true), PDType0Font.load(document, thaiBold, true)));
            }
        }

        private static InputStream requiredFont(String resource) {
            InputStream stream = PdfBoxOrderSummaryPdfRenderer.class.getResourceAsStream(resource);
            if (stream == null) {
                throw new IllegalStateException("Required PDF font is unavailable: " + resource);
            }
            return stream;
        }
    }

    /** A small, explicit fallback chain so the document can mix Thai and Latin text safely. */
    private record FontFace(PDFont latin, PDFont thai) {
        private PDFont forCodePoint(int codePoint) {
            return codePoint >= 0x0E00 && codePoint <= 0x0E7F ? thai : latin;
        }
    }

    private static final class Canvas {
        private final PDDocument pdf;
        private final Fonts fonts;
        private final OrderSummaryPdfDocument document;
        private PDPageContentStream content;
        private float y;

        private Canvas(PDDocument pdf, Fonts fonts, OrderSummaryPdfDocument document) {
            this.pdf = pdf;
            this.fonts = fonts;
            this.document = document;
        }

        private void openPage(boolean firstPage) throws IOException {
            close();
            PDPage page = new PDPage(PAGE_SIZE);
            pdf.addPage(page);
            content = new PDPageContentStream(pdf, page);
            text("PEGASUS", MARGIN, PAGE_HEIGHT - 37f, 25f, fonts.bold, PRIMARY);
            String roleTitle = document.role().name() + " ORDER SUMMARY";
            right(roleTitle, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 29f, 13f, fonts.bold, INK);
            right(roleCopy(document), PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 44f, 8.5f, fonts.regular, MUTED);
            y = PAGE_HEIGHT - 94f;
        }

        private void close() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }

        private boolean fits(float height) {
            return y - height >= BOTTOM_LIMIT;
        }

        private void ensure(float height) throws IOException {
            if (!fits(height)) {
                openPage(false);
            }
        }

        private void title(String value) throws IOException {
            text(value, MARGIN, y, 16f, fonts.bold, INK);
            move(21f);
        }

        private void section(String value) throws IOException {
            ensure(24f);
            text(value, MARGIN, y, 10f, fonts.bold, INK);
            move(14f);
        }

        private void label(String value, float x, float y) throws IOException {
            text(value, x, y, 7f, fonts.bold, MUTED);
        }

        private void text(String value, float x, float y, float size, FontFace font, Color color) throws IOException {
            PdfBoxOrderSummaryPdfRenderer.text(content, value, x, y, size, font, color);
        }

        private void right(String value, float x, float y, float size, FontFace font, Color color) throws IOException {
            PdfBoxOrderSummaryPdfRenderer.right(content, value, x, y, size, font, color);
        }

        private void fill(float x, float y, float width, float height, Color color) throws IOException {
            content.setNonStrokingColor(color);
            content.addRect(x, y, width, height);
            content.fill();
        }

        private void panel(float height) throws IOException {
            panelAt(MARGIN, y, CONTENT_WIDTH, height);
        }

        private void panelAt(float x, float top, float width, float height) throws IOException {
            content.setNonStrokingColor(PANEL);
            content.addRect(x, top - height, width, height);
            content.fill();
            content.setStrokingColor(BORDER);
            content.addRect(x, top - height, width, height);
            content.stroke();
        }

        private void line(float atY) throws IOException {
            line(atY, BORDER, 1f);
        }

        private void line(float atY, Color color, float width) throws IOException {
            content.setStrokingColor(color);
            content.setLineWidth(width);
            content.moveTo(MARGIN, atY);
            content.lineTo(MARGIN + CONTENT_WIDTH, atY);
            content.stroke();
            content.setLineWidth(1f);
        }

        private void badge(String value, float x, float top) throws IOException {
            float width = 86f;
            float height = 20f;
            fill(x, top - height, width, height, PRIMARY);
            text(value, x + 10f, top - 13.5f, 8f, fonts.bold, Color.WHITE);
        }

        private void partyCard(float x, float width, String title, String primary, String secondary, String tertiary) throws IOException {
            whitePanelAt(x, y, width, 78f);
            text(title, x + 14f, y - 16f, 9f, fonts.bold, INK);
            text(primary, x + 14f, y - 31f, 8.5f, fonts.regular, MUTED);
            if (secondary != null) {
                text(secondary, x + 14f, y - 44f, 8.5f, fonts.regular, MUTED);
            }
            if (tertiary != null) {
                text(tertiary, x + 14f, y - 57f, 8.5f, fonts.regular, MUTED);
            }
        }

        private void whitePanelAt(float x, float top, float width, float height) throws IOException {
            content.setNonStrokingColor(Color.WHITE);
            content.addRect(x, top - height, width, height);
            content.fill();
            content.setStrokingColor(BORDER);
            content.addRect(x, top - height, width, height);
            content.stroke();
        }

        private float y() {
            return y;
        }

        private void move(float amount) {
            y -= amount;
        }
    }
}
