package com.pegasus.pegasustcgapi.controller.order;

import com.pegasus.pegasustcgapi.service.order.pdf.OrderSummaryPdfRenderer.RenderedPdf;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class OrderSummaryPdfHttpResponse {

    private OrderSummaryPdfHttpResponse() {
    }

    static ResponseEntity<byte[]> from(RenderedPdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.content());
    }
}
