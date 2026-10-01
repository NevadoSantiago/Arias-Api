package com.arias.payments;

import java.util.List;

public record AdminPaymentReportDto(List<AdminPaymentRowDto> rows, AdminPaymentSummaryDto summary) {}
