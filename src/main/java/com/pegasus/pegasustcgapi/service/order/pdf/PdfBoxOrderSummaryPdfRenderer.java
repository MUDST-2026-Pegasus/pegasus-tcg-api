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
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
    private static final Color BORDER = new Color(227, 231, 232);

    @Override
    public RenderedPdf render(OrderSummaryPdfDocument document) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Fonts fonts = Fonts.load(pdf);
            Canvas canvas = new Canvas(pdf, fonts, document);
            canvas.openPage();

            drawOrderOverview(canvas, document);
            drawBuyer(canvas, document);
            drawSellerSections(canvas, document);
            drawTotals(canvas, document);

            canvas.close();
            drawFooters(pdf, fonts, document);
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
        if (document.role() == OrderSummaryPdfRole.SELLER && document.shipping() != null && document.shipping().estimatedDelivery() != null) {
            activity = "Placed " + formatShortDate(document.placedAt()) + " · Ship by " + document.shipping().estimatedDelivery();
        } else if (document.role() == OrderSummaryPdfRole.ADMIN) {
            activity = "Created " + formatShortDate(document.placedAt()) + " · Last updated " + formatShortDate(document.generatedAt());
        }
        canvas.text(activity, MARGIN, canvas.y() - 16f, 9f, canvas.fonts.regular, MUTED);
        canvas.badge(document.status(), PAGE_WIDTH - MARGIN - 86f, canvas.y() - 4f);
        canvas.move(42f);
    }

    private void drawBuyer(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        float cardWidth = (CONTENT_WIDTH - 16f) / 2f;
        OrderSummaryPdfDocument.Party buyer = document.buyer();
        List<String> buyerDetails = new ArrayList<>();
        if (document.role() == OrderSummaryPdfRole.SELLER) {
            String recipient = document.shipping() == null ? null : document.shipping().recipientName();
            if (recipient == null && buyer != null) recipient = buyer.displayName();
            if (recipient != null) buyerDetails.addAll(wrap(recipient, cardWidth - 26f, canvas.fonts.regular, 8.25f));
            if (document.shipping() != null && document.shipping().recipientPhone() != null) {
                buyerDetails.addAll(wrap("Phone: " + document.shipping().recipientPhone(), cardWidth - 26f, canvas.fonts.regular, 8.25f));
            }
        } else {
            if (buyer != null && buyer.username() != null) buyerDetails.addAll(wrap(buyer.username(), cardWidth - 26f, canvas.fonts.regular, 8.25f));
            if (buyer != null && buyer.displayName() != null) buyerDetails.addAll(wrap(buyer.displayName(), cardWidth - 26f, canvas.fonts.regular, 8.25f));
        }
        if (document.shipping() != null && document.shipping().address() != null) {
            buyerDetails.addAll(wrap(document.shipping().address(), cardWidth - 26f, canvas.fonts.regular, 8.25f));
        }
        String buyerTitle = document.role() == OrderSummaryPdfRole.SELLER ? "SHIP TO"
                : document.role() == OrderSummaryPdfRole.ADMIN ? "BUYER ACCOUNT" : "BUYER";
        String sellerName = "Seller unavailable";
        String sellerOrder = null;
        if (!canvas.document.sellerSections().isEmpty()) {
            OrderSummaryPdfDocument.SellerSection seller = canvas.document.sellerSections().getFirst();
            sellerName = seller.sellerName();
            sellerOrder = seller.sellerOrderNumber();
        }
        List<String> sellerDetails = new ArrayList<>();
        sellerDetails.add(sellerName);
        if (sellerOrder != null) sellerDetails.add("Store order #" + sellerOrder);
        if (document.role() == OrderSummaryPdfRole.ADMIN && document.payment() != null && document.payment().hasAnyValue()) {
            sellerDetails.add("Payment: " + valueOrUnavailable(document.payment().status()));
        }
        if (document.shipping() != null && document.shipping().carrierName() != null) {
            String carrier = document.shipping().carrierName();
            if (document.shipping().trackingNumber() != null) carrier += " · " + document.shipping().trackingNumber();
            sellerDetails.add(carrier);
        } else if (document.shipping() != null && document.shipping().trackingNumber() != null) {
            sellerDetails.add("Tracking: " + document.shipping().trackingNumber());
        }
        String sellerTitle = document.role() == OrderSummaryPdfRole.BUYER ? "SELLER" : "SELLER ACCOUNT";
        List<String> wrappedSellerDetails = new ArrayList<>();
        for (String detail : sellerDetails) {
            wrappedSellerDetails.addAll(wrap(detail, cardWidth - 26f, canvas.fonts.regular, 8.25f));
        }
        float cardHeight = Math.max(88f, 38f + 11.5f * Math.max(buyerDetails.size(), wrappedSellerDetails.size()));
        canvas.ensure(cardHeight + 18f);
        canvas.partyCard(MARGIN, cardWidth, cardHeight, buyerTitle, buyerDetails);
        canvas.partyCard(MARGIN + cardWidth + 16f, cardWidth, cardHeight, sellerTitle, wrappedSellerDetails);
        canvas.move(cardHeight + 18f);
    }

    private void drawSellerSections(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        canvas.section("ORDER ITEMS");
        for (OrderSummaryPdfDocument.SellerSection seller : document.sellerSections()) {
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
            List<String> productLines = wrap(item.productName(), 300f, canvas.fonts.bold, 8.5f);
            String details = itemDetails(item, currency);
            float rowHeight = Math.max(37f, (productLines.size() * 10.5f) + (details == null ? 12f : 22f));
            if (!canvas.fits(rowHeight)) {
                canvas.openPage();
                drawItemHeader(canvas);
            }
            float baseline = canvas.y() - 14f;
            for (String line : productLines) {
                canvas.text(line, MARGIN + 6f, baseline, 8.5f, canvas.fonts.bold, INK);
                baseline -= 10.5f;
            }
            if (details != null) {
                canvas.text(details, MARGIN + 6f, baseline, 7.25f, canvas.fonts.regular, MUTED);
            }
            canvas.text(String.valueOf(item.quantity()), MARGIN + 300f, canvas.y() - 15f, 8.5f, canvas.fonts.regular, INK);
            canvas.right(money(item.unitPrice(), currency), MARGIN + 410f, canvas.y() - 15f, 8.5f, canvas.fonts.regular, INK);
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
        canvas.text("QTY", MARGIN + 300f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.right("UNIT PRICE", MARGIN + 410f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.right("TOTAL", MARGIN + CONTENT_WIDTH - 2f, y - 13f, 7.5f, canvas.fonts.bold, MUTED);
        canvas.line(y - 20f);
        canvas.move(22f);
    }

    private void drawTotals(Canvas canvas, OrderSummaryPdfDocument document) throws IOException {
        OrderSummaryPdfDocument.Totals totals = document.totals();
        boolean buyerCopy = document.role() == OrderSummaryPdfRole.BUYER;
        float height = buyerCopy ? 86f : 102f;
        canvas.ensure(height + 12f);
        float labelX = MARGIN + CONTENT_WIDTH - 238f;
        float amountX = MARGIN + CONTENT_WIDTH;
        float row = canvas.y() - 12f;
        if (buyerCopy) {
            drawTotalRow(canvas, "Merchandise subtotal", totals.itemsSubtotal(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Shipping", totals.shippingFee(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Discount", totals.discountTotal().negate(), document.currency(), labelX, amountX, row, false);
            row -= 20f;
            drawTotalRow(canvas, "TOTAL PAID", totals.totalPaid(), document.currency(), labelX, amountX, row, true);
        } else if (document.role() == OrderSummaryPdfRole.SELLER) {
            drawTotalRow(canvas, "Items sold", totals.itemsSubtotal(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Shipping collected", totals.shippingFee(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Platform commission", totals.commissionAmount().negate(), document.currency(), labelX, amountX, row, false);
            row -= 20f;
            drawTotalRow(canvas, "NET RECEIVABLE", totals.sellerNetAmount(), document.currency(), labelX, amountX, row, true);
        } else {
            drawTotalRow(canvas, "Buyer paid", totals.totalPaid(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Seller merchandise gross", totals.itemsSubtotal(), document.currency(), labelX, amountX, row, false);
            row -= 17f;
            drawTotalRow(canvas, "Platform commission", totals.commissionAmount().negate(), document.currency(), labelX, amountX, row, false);
            row -= 20f;
            drawTotalRow(canvas, "SELLER NET", totals.sellerNetAmount(), document.currency(), labelX, amountX, row, true);
        }
        canvas.move(height + 8f);
    }

    private void drawTotalRow(
            Canvas canvas, String label, BigDecimal value, String currency, float labelX, float amountX, float y, boolean emphasize)
            throws IOException {
        FontFace font = emphasize ? canvas.fonts.bold : canvas.fonts.regular;
        float size = emphasize ? 9.5f : 8.5f;
        canvas.text(label, labelX, y, size, font, emphasize ? INK : MUTED);
        canvas.right(money(value, currency), amountX, y, emphasize ? 13f : size, font, INK);
    }

    private void drawFooters(PDDocument pdf, Fonts fonts, OrderSummaryPdfDocument document) throws IOException {
        int pageCount = pdf.getNumberOfPages();
        for (int index = 0; index < pageCount; index++) {
            PDPage page = pdf.getPage(index);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                String footer = switch (document.role()) {
                    case BUYER -> "PEGASUS TCG · support@pegasustcg.com";
                    case SELLER -> "PEGASUS TCG · Seller Operations";
                    case ADMIN -> "PEGASUS TCG · Internal Use Only";
                };
                text(content, "This order summary is not a receipt or tax invoice.", MARGIN, FOOTER_Y + 15f, 8f, fonts.regular, MUTED);
                text(content, footer, MARGIN, FOOTER_Y, 8.5f, fonts.regular, MUTED);
                right(content, "Page " + (index + 1) + " of " + pageCount, PAGE_WIDTH - MARGIN, FOOTER_Y, 8.5f, fonts.regular, MUTED);
            }
        }
    }

    private static String itemDetails(OrderSummaryPdfDocument.LineItem item, String currency) {
        List<String> details = new ArrayList<>();
        if (item.variantLabel() != null) details.add(item.variantLabel());
        if (item.condition() != null) details.add(item.condition());
        if (item.gameName() != null) details.add(item.gameName());
        if (item.discount().signum() != 0) details.add("Discount: -" + money(item.discount().abs(), currency));
        return details.isEmpty() ? null : String.join(" · ", details);
    }

    private static String money(BigDecimal value, String currency) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        if ("THB".equals(currency)) {
            BigDecimal normalized = amount.stripTrailingZeros();
            if (normalized.scale() <= 0) {
                return String.format(Locale.US, "฿%,.0f", amount);
            }
            return String.format(Locale.US, "฿%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
        }
        return String.format(Locale.US, "%s %,.2f", currency, amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatDate(OffsetDateTime value) {
        return value == null ? "Not available" : DATE_TIME.format(value);
    }

    private static String formatShortDate(OffsetDateTime value) {
        return value == null ? "Not available" : value.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
    }

    private static String valueOrUnavailable(String value) {
        return value == null ? "Not available" : value;
    }

    private static String roleCopy(OrderSummaryPdfDocument document) {
        return switch (document.role()) {
            case BUYER -> "Buyer copy · Order #" + document.orderNumber() + " · " + formatShortDate(document.generatedAt());
            case SELLER -> "Seller copy · Store order #" + valueOrUnavailable(document.sellerOrderNumber());
            case ADMIN -> "Internal admin copy · Order #" + document.orderNumber();
        };
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
        private final PDImageXObject brandIcon;
        private PDPageContentStream content;
        private float y;

        private Canvas(PDDocument pdf, Fonts fonts, OrderSummaryPdfDocument document) throws IOException {
            this.pdf = pdf;
            this.fonts = fonts;
            this.document = document;
            try (InputStream icon = PdfBoxOrderSummaryPdfRenderer.class
                    .getResourceAsStream("/images/pegasus-icon.jpg")) {
                if (icon == null) {
                    throw new IllegalStateException("Required PDF brand icon is unavailable");
                }
                brandIcon = PDImageXObject.createFromByteArray(pdf, icon.readAllBytes(), "Pegasus brand icon");
            }
        }

        private void openPage() throws IOException {
            close();
            PDPage page = new PDPage(PAGE_SIZE);
            pdf.addPage(page);
            content = new PDPageContentStream(pdf, page);
            content.drawImage(brandIcon, MARGIN, PAGE_HEIGHT - 46f, 29f, 29f);
            text("PEGASUS", MARGIN + 37f, PAGE_HEIGHT - 40f, 25f, fonts.bold, PRIMARY);
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
                openPage();
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
            content.setNonStrokingColor(PRIMARY);
            roundedRect(x, top, width, height, 9f);
            content.fill();
            float textX = x + (width - textWidth(fonts.bold, 8f, value)) / 2f;
            text(value, textX, top - 13.5f, 8f, fonts.bold, Color.WHITE);
        }

        private void partyCard(float x, float width, float height, String title, List<String> details) throws IOException {
            whitePanelAt(x, y, width, height);
            text(title, x + 14f, y - 16f, 9f, fonts.bold, INK);
            float detailY = y - 31f;
            if (details.isEmpty()) {
                text("Not available", x + 14f, detailY, 8.25f, fonts.regular, MUTED);
                return;
            }
            for (int index = 0; index < details.size(); index++) {
                text(details.get(index), x + 14f, detailY, 8.25f, fonts.regular, MUTED);
                detailY -= 11.5f;
            }
        }

        private void whitePanelAt(float x, float top, float width, float height) throws IOException {
            content.setNonStrokingColor(Color.WHITE);
            roundedRect(x, top, width, height, 6f);
            content.fill();
            content.setStrokingColor(BORDER);
            roundedRect(x, top, width, height, 6f);
            content.stroke();
        }

        private void roundedRect(float x, float top, float width, float height, float radius) throws IOException {
            float bottom = top - height;
            float r = Math.min(radius, Math.min(width, height) / 2f);
            float k = 0.55228475f * r;
            content.moveTo(x + r, top);
            content.lineTo(x + width - r, top);
            content.curveTo(x + width - r + k, top, x + width, top - r + k, x + width, top - r);
            content.lineTo(x + width, bottom + r);
            content.curveTo(x + width, bottom + r - k, x + width - r + k, bottom, x + width - r, bottom);
            content.lineTo(x + r, bottom);
            content.curveTo(x + r - k, bottom, x, bottom + r - k, x, bottom + r);
            content.lineTo(x, top - r);
            content.curveTo(x, top - r + k, x + r - k, top, x + r, top);
            content.closePath();
        }

        private float y() {
            return y;
        }

        private void move(float amount) {
            y -= amount;
        }
    }
}
