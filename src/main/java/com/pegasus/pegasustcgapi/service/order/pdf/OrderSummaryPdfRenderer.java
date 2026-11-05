package com.pegasus.pegasustcgapi.service.order.pdf;

import java.util.Arrays;
import java.util.Objects;

/** Renders one role-scoped order summary and supplies a safe download filename. */
public interface OrderSummaryPdfRenderer {

    RenderedPdf render(OrderSummaryPdfDocument document);

    record RenderedPdf(String fileName, byte[] content) {
        public RenderedPdf {
            fileName = Objects.requireNonNull(fileName, "fileName is required");
            content = Arrays.copyOf(Objects.requireNonNull(content, "content is required"), content.length);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }
}
