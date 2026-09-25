package com.pegasus.pegasustcgapi.dto;

/**
 * One place in the trending rail.
 *
 * @param rank      1 for the top card
 * @param unitsSold cards sold over the window that was asked about; 0 when the
 *                  place was filled by how many sellers are offering it instead
 */
public record TrendingProductResponse(int rank, long unitsSold, ProductSummaryResponse product) {
}
