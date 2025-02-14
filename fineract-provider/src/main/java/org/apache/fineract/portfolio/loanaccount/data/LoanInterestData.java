/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.data;

import java.math.BigDecimal;
import lombok.Data;
import org.apache.fineract.infrastructure.core.config.MapstructMapperConfig;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.mapstruct.Mapping;

@Data
public class LoanInterestData {

    private final BigDecimal interestCharged;
    private final BigDecimal interestPaid;
    private final BigDecimal interestWaived;
    private final BigDecimal interestWrittenOff;
    private final BigDecimal interestOutstanding;

    @org.mapstruct.Mapper(config = MapstructMapperConfig.class)
    public interface Mapper {

        @Mapping(source = "totalInterestCharged", target = "interestCharged")
        @Mapping(source = "totalInterestRepaid", target = "interestPaid")
        @Mapping(source = "totalInterestWaived", target = "interestWaived")
        @Mapping(source = "totalInterestWrittenOff", target = "interestWrittenOff")
        @Mapping(source = "totalInterestOutstanding", target = "interestOutstanding")
        LoanInterestData map(LoanSummary source);
    }
}
