package com.arias.companies;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CompanyCategoryPriceId implements Serializable {
    private Long companyId;
    private Long categoryId;
}
