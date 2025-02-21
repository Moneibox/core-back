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
package org.apache.fineract.portfolio.loanproduct.calc;

import jakarta.annotation.Nonnull;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariationType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelRepaymentPeriod;
import org.apache.fineract.portfolio.loanproduct.calc.data.EmiAdjustment;
import org.apache.fineract.portfolio.loanproduct.calc.data.EmiChangeOperation;
import org.apache.fineract.portfolio.loanproduct.calc.data.InterestPeriod;
import org.apache.fineract.portfolio.loanproduct.calc.data.OutstandingDetails;
import org.apache.fineract.portfolio.loanproduct.calc.data.PeriodDueDetails;
import org.apache.fineract.portfolio.loanproduct.calc.data.ProgressiveLoanInterestScheduleModel;
import org.apache.fineract.portfolio.loanproduct.calc.data.RepaymentPeriod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductMinimumRepaymentScheduleRelatedDetail;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class ProgressiveEMICalculator implements EMICalculator {

    private static final BigDecimal DIVISOR_100 = new BigDecimal("100");
    private static final BigDecimal ONE_WEEK_IN_DAYS = BigDecimal.valueOf(7);

    @Override
    @NotNull
    public ProgressiveLoanInterestScheduleModel generatePeriodInterestScheduleModel(@NotNull List<LoanScheduleModelRepaymentPeriod> periods,
            @NotNull LoanProductMinimumRepaymentScheduleRelatedDetail loanProductRelatedDetail,
            List<LoanTermVariationsData> loanTermVariations, final Integer installmentAmountInMultiplesOf, final MathContext mc) {
        return generateInterestScheduleModel(periods, LoanScheduleModelRepaymentPeriod::periodFromDate,
                LoanScheduleModelRepaymentPeriod::periodDueDate, loanProductRelatedDetail, loanTermVariations,
                installmentAmountInMultiplesOf, mc);
    }

    @Override
    @NotNull
    public ProgressiveLoanInterestScheduleModel generateInstallmentInterestScheduleModel(
            @NotNull List<LoanRepaymentScheduleInstallment> installments,
            @NotNull LoanProductMinimumRepaymentScheduleRelatedDetail loanProductRelatedDetail,
            List<LoanTermVariationsData> loanTermVariations, final Integer installmentAmountInMultiplesOf, final MathContext mc) {
        installments = installments.stream().filter(installment -> !installment.isDownPayment() && !installment.isAdditional()).toList();
        return generateInterestScheduleModel(installments, LoanRepaymentScheduleInstallment::getFromDate,
                LoanRepaymentScheduleInstallment::getDueDate, loanProductRelatedDetail, loanTermVariations, installmentAmountInMultiplesOf,
                mc);
    }

    @NotNull
    private <T> ProgressiveLoanInterestScheduleModel generateInterestScheduleModel(@NotNull List<T> periods, Function<T, LocalDate> from,
            Function<T, LocalDate> to, @NotNull LoanProductMinimumRepaymentScheduleRelatedDetail loanProductRelatedDetail,
            List<LoanTermVariationsData> loanTermVariations, final Integer installmentAmountInMultiplesOf, final MathContext mc) {
        final Money zero = Money.zero(loanProductRelatedDetail.getCurrencyData(), mc);
        final AtomicReference<RepaymentPeriod> prev = new AtomicReference<>();
        List<RepaymentPeriod> repaymentPeriods = periods.stream().map(e -> {
            RepaymentPeriod rp = RepaymentPeriod.create(prev.get(), from.apply(e), to.apply(e), zero, mc);
            prev.set(rp);
            return rp;
        }).toList();
        return new ProgressiveLoanInterestScheduleModel(repaymentPeriods, loanProductRelatedDetail, loanTermVariations,
                installmentAmountInMultiplesOf, mc);
    }

    @Override
    public Optional<RepaymentPeriod> findRepaymentPeriod(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final LocalDate repaymentPeriodDueDate) {
        if (scheduleModel == null) {
            return Optional.empty();
        }
        return scheduleModel.findRepaymentPeriodByDueDate(repaymentPeriodDueDate);
    }

    /**
     * Add disbursement to Interest Period
     */
    @Override
    public void addDisbursement(final ProgressiveLoanInterestScheduleModel scheduleModel, final LocalDate disbursementDueDate,
            final Money disbursedAmount) {
        addDisbursement(scheduleModel, EmiChangeOperation.disburse(disbursementDueDate, disbursedAmount));
    }

    private void addDisbursement(final ProgressiveLoanInterestScheduleModel scheduleModel, final EmiChangeOperation operation) {
        scheduleModel
                .changeOutstandingBalanceAndUpdateInterestPeriods(operation.getSubmittedOnDate(), operation.getAmount(),
                        scheduleModel.zero())
                .ifPresent((repaymentPeriod) -> calculateEMIValueAndRateFactors(
                        getEffectiveRepaymentDueDate(scheduleModel, repaymentPeriod, operation.getSubmittedOnDate()), scheduleModel,
                        operation));
    }

    private LocalDate getEffectiveRepaymentDueDate(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final RepaymentPeriod changedRepaymentPeriod, final LocalDate operationDueDate) {
        final boolean isRelatedToNextRepaymentPeriod = changedRepaymentPeriod.getDueDate().isEqual(operationDueDate);
        if (isRelatedToNextRepaymentPeriod) {
            final Optional<RepaymentPeriod> nextRepaymentPeriod = scheduleModel.repaymentPeriods().stream()
                    .filter(repaymentPeriod -> changedRepaymentPeriod.equals(repaymentPeriod.getPrevious().orElse(null))).findFirst();
            if (nextRepaymentPeriod.isPresent()) {
                return nextRepaymentPeriod.get().getDueDate();
            }
            // Currently N+1 scenario is not supported. Disbursement on Last Repayment due date affects the last
            // repayment period.
        }
        return changedRepaymentPeriod.getDueDate();
    }

    @Override
    public void changeInterestRate(final ProgressiveLoanInterestScheduleModel scheduleModel, final LocalDate newInterestSubmittedOnDate,
            final BigDecimal newInterestRate) {
        changeInterestRate(scheduleModel, EmiChangeOperation.changeInterestRate(newInterestSubmittedOnDate, newInterestRate));
    }

    private void changeInterestRate(final ProgressiveLoanInterestScheduleModel scheduleModel, final EmiChangeOperation operation) {
        final LocalDate interestRateChangeEffectiveDate = operation.getSubmittedOnDate().minusDays(1);
        scheduleModel.addInterestRate(interestRateChangeEffectiveDate, operation.getInterestRate());
        scheduleModel
                .changeOutstandingBalanceAndUpdateInterestPeriods(interestRateChangeEffectiveDate, scheduleModel.zero(),
                        scheduleModel.zero())
                .ifPresent(repaymentPeriod -> calculateEMIValueAndRateFactors(
                        getEffectiveRepaymentDueDate(scheduleModel, repaymentPeriod, interestRateChangeEffectiveDate), scheduleModel,
                        operation));
    }

    @Override
    public void addBalanceCorrection(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate balanceCorrectionDate,
            Money balanceCorrectionAmount) {
        scheduleModel.changeOutstandingBalanceAndUpdateInterestPeriods(balanceCorrectionDate, scheduleModel.zero(), balanceCorrectionAmount)
                .ifPresent(repaymentPeriod -> {
                    calculateRateFactorForRepaymentPeriod(repaymentPeriod, scheduleModel);
                    calculateOutstandingBalance(scheduleModel);
                    calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
                });
    }

    @Override
    public void payInterest(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate repaymentPeriodDueDate, LocalDate transactionDate,
            Money interestAmount) {
        findRepaymentPeriod(scheduleModel, repaymentPeriodDueDate).ifPresent(rp -> rp.addPaidInterestAmount(interestAmount));
        calculateOutstandingBalance(scheduleModel);
        calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
    }

    @Override
    public void payPrincipal(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate repaymentPeriodDueDate,
            LocalDate transactionDate, Money principalAmount) {
        if (MathUtil.isEmpty(principalAmount)) {
            return;
        }
        Optional<RepaymentPeriod> repaymentPeriod = findRepaymentPeriod(scheduleModel, repaymentPeriodDueDate);
        boolean transactionDateIsBefore = transactionDate.isBefore(repaymentPeriod.get().getFromDate());
        repaymentPeriod.ifPresent(rp -> rp.addPaidPrincipalAmount(principalAmount));
        // If it is paid late, we need to calculate with the period due date
        LocalDate balanceCorrectionDate = DateUtils.isBefore(repaymentPeriodDueDate, transactionDate) ? repaymentPeriodDueDate
                : transactionDate;
        addBalanceCorrection(scheduleModel, balanceCorrectionDate, principalAmount.negated());
        if (scheduleModel.isEMIRecalculationEnabled()) {
            repaymentPeriod.ifPresent(rp -> {
                // If any period total paid > calculated EMI, then set EMI to total paid -> effectively it is marked as
                // fully paid
                if (transactionDateIsBefore && rp.getTotalPaidAmount().isGreaterThan(rp.getEmiPlusChargeback())) {
                    rp.setEmi(rp.getTotalPaidAmount().minus(rp.getTotalChargebackAmount()));
                } else if (transactionDateIsBefore
                        && rp.getTotalPaidAmount().isEqualTo(rp.getOriginalEmi().add(rp.getTotalChargebackAmount()))) {
                    rp.setEmi(rp.getTotalPaidAmount().minus(rp.getTotalChargebackAmount()));
                }
                calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
            });
        }
    }

    private void addChargebackAmountsToInterestPeriod(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate transactionDate,
            Money chargebackPrincipalAmount, Money chargeBackInterestAmount) {
        scheduleModel.repaymentPeriods().stream().filter(checkRepaymentPeriodIsInChargebackRange(scheduleModel, transactionDate))
                .findFirst()
                .flatMap(repaymentPeriod -> repaymentPeriod.getInterestPeriods().stream()
                        .filter(interestPeriod -> interestPeriod.getFromDate().equals(transactionDate)).reduce((v1, v2) -> v2))
                .ifPresent(interestPeriod -> {
                    interestPeriod.addChargebackPrincipalAmount(chargebackPrincipalAmount);
                    interestPeriod.addChargebackInterestAmount(chargeBackInterestAmount);
                });
    }

    @Nonnull
    private static Predicate<RepaymentPeriod> checkRepaymentPeriodIsInChargebackRange(ProgressiveLoanInterestScheduleModel scheduleModel,
            LocalDate transactionDate) {
        return repaymentPeriod -> scheduleModel.isLastRepaymentPeriod(repaymentPeriod)
                ? !transactionDate.isBefore(repaymentPeriod.getFromDate()) && !transactionDate.isAfter(repaymentPeriod.getDueDate())
                : !transactionDate.isBefore(repaymentPeriod.getFromDate()) && transactionDate.isBefore(repaymentPeriod.getDueDate());
    }

    @Override
    public void chargebackPrincipal(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate transactionDate,
            Money chargebackPrincipalAmount) {
        addChargeback(scheduleModel, transactionDate, chargebackPrincipalAmount, scheduleModel.zero());
    }

    @Override
    public void chargebackInterest(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate transactionDate,
            Money chargebackInterestAmount) {
        addChargeback(scheduleModel, transactionDate, scheduleModel.zero(), chargebackInterestAmount);
    }

    private void addChargeback(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate transactionDate,
            Money chargebackPrincipalAmount, Money chargebackInterestAmount) {
        scheduleModel.changeOutstandingBalanceAndUpdateInterestPeriods(transactionDate, scheduleModel.zero(), chargebackPrincipalAmount)
                .ifPresent(repaymentPeriod -> {
                    addChargebackAmountsToInterestPeriod(scheduleModel, transactionDate, chargebackPrincipalAmount,
                            chargebackInterestAmount);
                    calculateRateFactorForRepaymentPeriod(repaymentPeriod, scheduleModel);
                    calculateOutstandingBalance(scheduleModel);
                    calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
                });
    }

    /**
     * This method gives back the maximum of the due principal and maximum of the due interest for a requested day.
     */
    @Override
    @NotNull
    public PeriodDueDetails getDueAmounts(@NotNull ProgressiveLoanInterestScheduleModel scheduleModel, @NotNull LocalDate periodDueDate,
            @NotNull LocalDate targetDate) {
        ProgressiveLoanInterestScheduleModel recalculatedScheduleModelTillDate = recalculateScheduleModelTillDate(scheduleModel,
                periodDueDate, targetDate);
        RepaymentPeriod repaymentPeriod = recalculatedScheduleModelTillDate.findRepaymentPeriodByDueDate(periodDueDate).orElseThrow();
        boolean multiplePeriodIsUnpaid = recalculatedScheduleModelTillDate.repaymentPeriods().stream().filter(rp -> !rp.isFullyPaid())
                .count() > 1L;
        if (multiplePeriodIsUnpaid && !targetDate.isAfter(repaymentPeriod.getFromDate())) {
            repaymentPeriod.setEmi(repaymentPeriod.getOriginalEmi());
        }

        return new PeriodDueDetails(repaymentPeriod.getEmi(), //
                repaymentPeriod.getDuePrincipal(), //
                repaymentPeriod.getDueInterest()); //
    }

    @Override
    @NotNull
    public Money getPeriodInterestTillDate(@NotNull ProgressiveLoanInterestScheduleModel scheduleModel, @NotNull LocalDate periodDueDate,
            @NotNull LocalDate targetDate, boolean includeChargebackInterest) {
        ProgressiveLoanInterestScheduleModel recalculatedScheduleModelTillDate = recalculateScheduleModelTillDate(scheduleModel,
                periodDueDate, targetDate);
        RepaymentPeriod repaymentPeriod = recalculatedScheduleModelTillDate.findRepaymentPeriodByDueDate(periodDueDate).orElseThrow();
        return includeChargebackInterest ? repaymentPeriod.getCalculatedDueInterest()
                : repaymentPeriod.getCalculatedDueInterest().minus(repaymentPeriod.getChargebackInterest(),
                        recalculatedScheduleModelTillDate.mc());
    }

    @Override
    public Money getOutstandingLoanBalanceOfPeriod(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate periodDueDate,
            LocalDate targetDate) {
        ProgressiveLoanInterestScheduleModel recalculatedScheduleModelTillDate = recalculateScheduleModelTillDate(scheduleModel,
                periodDueDate, targetDate);
        RepaymentPeriod repaymentPeriod = recalculatedScheduleModelTillDate.findRepaymentPeriodByDueDate(periodDueDate).orElseThrow();

        return repaymentPeriod.getOutstandingLoanBalance();
    }

    @Override
    public OutstandingDetails getOutstandingAmountsTillDate(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate targetDate) {
        MathContext mc = scheduleModel.mc();
        ProgressiveLoanInterestScheduleModel scheduleModelCopy = scheduleModel.deepCopy(mc);
        // TODO use findInterestPeriod
        scheduleModelCopy.repaymentPeriods().stream()//
                .filter(rp -> targetDate.isAfter(rp.getFromDate()) && !targetDate.isAfter(rp.getDueDate())).findFirst()//
                .flatMap(rp -> rp.getInterestPeriods().stream()//
                        .filter(ip -> targetDate.isAfter(ip.getFromDate()) && !targetDate.isAfter(ip.getDueDate())) //
                        .reduce((one, two) -> two))
                .ifPresent(ip -> ip.setDueDate(targetDate)); //

        calculateRateFactorForPeriods(scheduleModelCopy.repaymentPeriods(), scheduleModelCopy);
        scheduleModelCopy.repaymentPeriods()
                .forEach(rp -> rp.getInterestPeriods().stream().filter(ip -> targetDate.isBefore(ip.getDueDate())).forEach(ip -> {
                    ip.setRateFactor(BigDecimal.ZERO);
                    ip.setRateFactorTillPeriodDueDate(BigDecimal.ZERO);
                }));
        calculateOutstandingBalance(scheduleModelCopy);
        calculateLastUnpaidRepaymentPeriodEMI(scheduleModelCopy);

        Money totalOutstandingPrincipal = MathUtil
                .negativeToZero(scheduleModelCopy.getTotalDuePrincipal().minus(scheduleModelCopy.getTotalPaidPrincipal()));
        Money totalOutstandingInterest = MathUtil
                .negativeToZero(scheduleModelCopy.getTotalDueInterest().minus(scheduleModelCopy.getTotalPaidInterest()));
        return new OutstandingDetails(totalOutstandingPrincipal, totalOutstandingInterest);
    }

    @Override
    public void calculateRateFactorForRepaymentPeriod(final RepaymentPeriod repaymentPeriod,
            final ProgressiveLoanInterestScheduleModel scheduleModel) {
        repaymentPeriod.getInterestPeriods().forEach(interestPeriod -> {
            interestPeriod.setRateFactor(calculateRateFactorPerPeriod(scheduleModel, repaymentPeriod, interestPeriod.getFromDate(),
                    interestPeriod.getDueDate()));
            interestPeriod.setRateFactorTillPeriodDueDate(calculateRateFactorPerPeriodForInterest(scheduleModel, repaymentPeriod,
                    interestPeriod.getFromDate(), repaymentPeriod.getDueDate()));
        });
    }

    @NotNull
    private ProgressiveLoanInterestScheduleModel recalculateScheduleModelTillDate(
            @NotNull ProgressiveLoanInterestScheduleModel scheduleModel, @NotNull LocalDate periodDueDate, @NotNull LocalDate targetDate) {
        MathContext mc = scheduleModel.mc();
        ProgressiveLoanInterestScheduleModel scheduleModelCopy = scheduleModel.deepCopy(mc);
        RepaymentPeriod repaymentPeriod = scheduleModelCopy.findRepaymentPeriodByDueDate(periodDueDate).orElseThrow();

        LocalDate adjustedTargetDate = targetDate;
        InterestPeriod interestPeriod;
        if (!targetDate.isAfter(repaymentPeriod.getFromDate())) {
            interestPeriod = repaymentPeriod.getFirstInterestPeriod();
            adjustedTargetDate = repaymentPeriod.getFromDate();
        } else if (targetDate.isAfter(repaymentPeriod.getDueDate())) {
            interestPeriod = repaymentPeriod.getLastInterestPeriod();
            adjustedTargetDate = repaymentPeriod.getDueDate();
        } else {
            interestPeriod = repaymentPeriod.findInterestPeriod(targetDate).orElseThrow();
        }
        // TODO use findInterestPeriod
        scheduleModelCopy.findRepaymentPeriod(targetDate)//
                .flatMap(rp -> rp.findInterestPeriod(targetDate)).ifPresent(ip -> ip.setDueDate(targetDate)); //
        interestPeriod.setDueDate(adjustedTargetDate);
        int index = repaymentPeriod.getInterestPeriods().indexOf(interestPeriod);
        int nextIdx = index + 1;
        boolean thereIsInterestPeriodFromDateOnTargetDate = repaymentPeriod.getInterestPeriods().size() > nextIdx
                && repaymentPeriod.getInterestPeriods().get(nextIdx).getFromDate().isEqual(targetDate);
        if (thereIsInterestPeriodFromDateOnTargetDate) {
            // NOTE: If there is a next interest period with fromDate on the target date
            // then the related chargeback amounts comes from the next interest period too.
            InterestPeriod nextInterestPeriod = repaymentPeriod.getInterestPeriods().get(nextIdx);
            interestPeriod.addChargebackPrincipalAmount(nextInterestPeriod.getChargebackPrincipal());
            interestPeriod.addChargebackInterestAmount(nextInterestPeriod.getChargebackInterest());
        }
        repaymentPeriod.getInterestPeriods().subList(nextIdx, repaymentPeriod.getInterestPeriods().size()).clear();
        scheduleModelCopy.repaymentPeriods().forEach(rp -> rp.getInterestPeriods().removeIf(ip -> ip.getDueDate().isAfter(targetDate)));
        calculateRateFactorForPeriods(scheduleModelCopy.repaymentPeriods(), scheduleModelCopy);
        calculateOutstandingBalance(scheduleModelCopy);
        calculateLastUnpaidRepaymentPeriodEMI(scheduleModelCopy);

        return scheduleModelCopy;
    }

    /**
     * Calculate Equal Monthly Installment value and Rate Factor -1 values for calculate Interest
     */
    private void calculateEMIValueAndRateFactors(final LocalDate calculateFromRepaymentPeriodDueDate,
            final ProgressiveLoanInterestScheduleModel scheduleModel, final EmiChangeOperation operation) {
        final List<RepaymentPeriod> relatedRepaymentPeriods = scheduleModel.getRelatedRepaymentPeriods(calculateFromRepaymentPeriodDueDate);
        final boolean onlyOnActualModelShouldApply = scheduleModel.isEmpty()
                || operation.getAction() == EmiChangeOperation.Action.INTEREST_RATE_CHANGE || scheduleModel.isCopy();

        calculateRateFactorForPeriods(relatedRepaymentPeriods, scheduleModel);
        calculateOutstandingBalance(scheduleModel);
        if (onlyOnActualModelShouldApply) {
            calculateEMIOnActualModel(relatedRepaymentPeriods, scheduleModel);
        } else {
            calculateEMIOnNewModelAndMerge(relatedRepaymentPeriods, scheduleModel, operation);
        }
        calculateOutstandingBalance(scheduleModel);
        calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
        if (onlyOnActualModelShouldApply && (scheduleModel.loanTermVariations() == null
                || scheduleModel.loanTermVariations().get(LoanTermVariationType.DUE_DATE) == null)) {
            checkAndAdjustEmiIfNeededOnRelatedRepaymentPeriods(scheduleModel, relatedRepaymentPeriods);
        }
    }

    private void calculateLastUnpaidRepaymentPeriodEMI(ProgressiveLoanInterestScheduleModel scheduleModel) {
        MathContext mc = scheduleModel.mc();
        Money totalDueInterest = scheduleModel.repaymentPeriods().stream().map(RepaymentPeriod::getDueInterest).reduce(scheduleModel.zero(),
                (m1, m2) -> m1.plus(m2, mc)); // 1.46
        Money totalEMI = scheduleModel.repaymentPeriods().stream().map(RepaymentPeriod::getEmiPlusChargeback).reduce(scheduleModel.zero(),
                (m1, m2) -> m1.plus(m2, mc)); // 101.48
        Money totalDisbursedAmount = scheduleModel.repaymentPeriods().stream()
                .flatMap(rp -> rp.getInterestPeriods().stream().map(InterestPeriod::getDisbursementAmount))
                .reduce(scheduleModel.zero(), (m1, m2) -> m1.plus(m2, mc)) // 100
                .plus(scheduleModel.getTotalChargebackPrincipal(), mc); //

        Money diff = totalDisbursedAmount.plus(totalDueInterest, mc).minus(totalEMI, mc);
        Optional<RepaymentPeriod> findLastUnpaidRepaymentPeriod = scheduleModel.repaymentPeriods().stream().filter(rp -> !rp.isFullyPaid())
                .reduce((first, second) -> second);
        findLastUnpaidRepaymentPeriod.ifPresent(repaymentPeriod -> {
            repaymentPeriod.setEmi(repaymentPeriod.getEmi().add(diff, mc));
            if (repaymentPeriod.getEmi()
                    .isLessThan(repaymentPeriod.getTotalPaidAmount().minus(repaymentPeriod.getTotalChargebackAmount(), mc))) {
                repaymentPeriod.setEmi(repaymentPeriod.getTotalPaidAmount().minus(repaymentPeriod.getTotalChargebackAmount(), mc));
                calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
            }
        });
    }

    private void calculateOutstandingBalance(ProgressiveLoanInterestScheduleModel scheduleModel) {
        scheduleModel.repaymentPeriods().forEach(rp -> rp.getInterestPeriods().forEach(InterestPeriod::updateOutstandingLoanBalance));
    }

    private void checkAndAdjustEmiIfNeededOnRelatedRepaymentPeriods(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final List<RepaymentPeriod> relatedRepaymentPeriods) {
        MathContext mc = scheduleModel.mc();
        ProgressiveLoanInterestScheduleModel newScheduleModel = null;
        int adjustCounter = 1;
        EmiAdjustment emiAdjustment;

        do {
            emiAdjustment = getEmiAdjustment(relatedRepaymentPeriods);
            if (!emiAdjustment.shouldBeAdjusted()) {
                break;
            }
            Money adjustedEqualMonthlyInstallmentValue = applyInstallmentAmountInMultiplesOf(scheduleModel, emiAdjustment.adjustedEmi());
            if (adjustedEqualMonthlyInstallmentValue.isEqualTo(emiAdjustment.originalEmi())) {
                break;
            }
            if (newScheduleModel == null) {
                newScheduleModel = scheduleModel.deepCopy(mc);
            }
            final LocalDate relatedPeriodsFirstDueDate = relatedRepaymentPeriods.get(0).getDueDate();
            newScheduleModel.repaymentPeriods().forEach(period -> {
                if (!period.getDueDate().isBefore(relatedPeriodsFirstDueDate)
                        && !adjustedEqualMonthlyInstallmentValue.isLessThan(period.getTotalPaidAmount())) {
                    period.setEmi(adjustedEqualMonthlyInstallmentValue);
                    period.setOriginalEmi(adjustedEqualMonthlyInstallmentValue);
                }
            });
            calculateOutstandingBalance(newScheduleModel);
            calculateLastUnpaidRepaymentPeriodEMI(newScheduleModel);
            if (!getEmiAdjustment(newScheduleModel.repaymentPeriods()).hasLessEmiDifference(emiAdjustment)) {
                break;
            }

            final Iterator<RepaymentPeriod> relatedPeriodFromNewModelIterator = newScheduleModel.repaymentPeriods().stream()//
                    .filter(period -> !period.getDueDate().isBefore(relatedPeriodsFirstDueDate))//
                    .toList().iterator();//

            relatedRepaymentPeriods.forEach(relatedRepaymentPeriod -> {
                if (!relatedPeriodFromNewModelIterator.hasNext()) {
                    return;
                }
                final RepaymentPeriod newRepaymentPeriod = relatedPeriodFromNewModelIterator.next();
                relatedRepaymentPeriod.setEmi(newRepaymentPeriod.getEmi());
                relatedRepaymentPeriod.setOriginalEmi(newRepaymentPeriod.getEmi());
            });
            calculateOutstandingBalance(scheduleModel);
            adjustCounter++;
        } while (adjustCounter <= 3);
    }

    /**
     * Convert Interest Percentage to fraction of 1
     *
     * @param interestRate
     *            Interest Rate in Percentage
     *
     * @return Rate Interest Rate in fraction format
     */
    private BigDecimal calcNominalInterestRatePercentage(final BigDecimal interestRate, MathContext mc) {
        return MathUtil.nullToZero(interestRate).divide(DIVISOR_100, mc);
    }

    /**
     * * Calculate rate factors from ONLY repayment periods
     */
    private void calculateRateFactorForPeriods(final List<RepaymentPeriod> repaymentPeriods,
            final ProgressiveLoanInterestScheduleModel scheduleModel) {
        repaymentPeriods.forEach(repaymentPeriod -> calculateRateFactorForRepaymentPeriod(repaymentPeriod, scheduleModel));
    }

    BigDecimal calculateRateFactorPerPeriodForInterest(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final RepaymentPeriod repaymentPeriod, final LocalDate interestPeriodFromDate, final LocalDate interestPeriodDueDate) {
        final MathContext mc = scheduleModel.mc();
        final LoanProductMinimumRepaymentScheduleRelatedDetail loanProductRelatedDetail = scheduleModel.loanProductRelatedDetail();
        final BigDecimal interestRate = calcNominalInterestRatePercentage(scheduleModel.getInterestRate(interestPeriodFromDate),
                scheduleModel.mc());
        final DaysInYearType daysInYearType = DaysInYearType.fromInt(loanProductRelatedDetail.getDaysInYearType());
        final DaysInMonthType daysInMonthType = DaysInMonthType.fromInt(loanProductRelatedDetail.getDaysInMonthType());
        final PeriodFrequencyType repaymentFrequency = loanProductRelatedDetail.getRepaymentPeriodFrequencyType();

        BigDecimal daysInYear = BigDecimal.valueOf(daysInYearType.getNumberOfDays(interestPeriodFromDate));
        final BigDecimal actualDaysInPeriod = BigDecimal
                .valueOf(DateUtils.getDifferenceInDays(interestPeriodFromDate, interestPeriodDueDate));
        final BigDecimal calculatedDaysInPeriod = BigDecimal
                .valueOf(DateUtils.getDifferenceInDays(repaymentPeriod.getFromDate(), repaymentPeriod.getDueDate()));
        final int numberOfYearsDifferenceInPeriod = interestPeriodDueDate.getYear() - interestPeriodFromDate.getYear();
        final boolean partialPeriodCalculationNeeded = daysInYearType == DaysInYearType.ACTUAL && numberOfYearsDifferenceInPeriod > 0;

        // TODO check: loanApplicationTerms.calculatePeriodsBetweenDates(startDate, endDate); // calculate period data
        // TODO review: (repayment frequency: days, weeks, years; validation day is month fix 30)
        // TODO refactor this logic to represent in interest period
        if (partialPeriodCalculationNeeded) {
            final BigDecimal cumulatedPeriodFractions = calculatePeriodFractions(scheduleModel, interestPeriodFromDate,
                    interestPeriodDueDate, mc);
            return rateFactorByRepaymentPartialPeriod(interestRate, BigDecimal.ONE, cumulatedPeriodFractions, BigDecimal.ONE,
                    BigDecimal.ONE, mc);
        }

        if (daysInMonthType.equals(DaysInMonthType.ACTUAL)) {
            return rateFactorByRepaymentPeriod(interestRate, actualDaysInPeriod, BigDecimal.ONE, daysInYear, BigDecimal.ONE, BigDecimal.ONE,
                    mc);
        } else if (daysInMonthType.isDaysInMonth_30()) {
            BigDecimal periodRatio = switch (repaymentFrequency) {
                case YEARS -> calculatePeriodRatio(scheduleModel, repaymentPeriod, ChronoUnit.YEARS, mc);
                case MONTHS -> calculatePeriodRatio(scheduleModel, repaymentPeriod, ChronoUnit.MONTHS, mc);
                case WEEKS -> calculatePeriodRatio(scheduleModel, repaymentPeriod, ChronoUnit.WEEKS, mc);
                case DAYS -> calculatePeriodRatio(scheduleModel, repaymentPeriod, ChronoUnit.DAYS, mc);
                default -> throw new UnsupportedOperationException("Unsupported repayment frequency: " + repaymentFrequency);
            };

            return calculateRateFactorPerPeriodBasedOnRepaymentFrequency(interestRate, repaymentFrequency, periodRatio,
                    BigDecimal.valueOf(30), daysInYear, actualDaysInPeriod, calculatedDaysInPeriod, mc);
        }
        throw new UnsupportedOperationException(
                "Unsupported combination: Days in year: " + daysInYearType + ", days in month: " + daysInMonthType);
    }

    private static BigDecimal calculatePeriodRatio(ProgressiveLoanInterestScheduleModel scheduleModel, RepaymentPeriod repaymentPeriod,
            ChronoUnit chronoUnit, MathContext mc) {

        LocalDate seedDate = calculateSeedDate(scheduleModel, repaymentPeriod);
        int numberOfPeriodBetweenSeedDateAndActualRepaymentPeriod = switch (chronoUnit) {
            case DAYS, WEEKS, YEARS -> DateUtils.getExactDifference(seedDate, repaymentPeriod.getFromDate(), chronoUnit);
            case MONTHS -> {
                int seedDateDay = seedDate.getDayOfMonth();
                int targetDateDay = repaymentPeriod.getFromDate().getDayOfMonth();
                int targetDateLastDay = ((LocalDate) TemporalAdjusters.lastDayOfMonth().adjustInto(repaymentPeriod.getFromDate()))
                        .getDayOfMonth();
                // In case target date is the last day of the month and the seed date day is later than the target date
                // day, we need to move it by 1 days
                if (targetDateLastDay == targetDateDay && seedDateDay > targetDateDay) {
                    yield DateUtils.getExactDifference(seedDate, repaymentPeriod.getFromDate().plusDays(1), chronoUnit);
                } else {
                    yield DateUtils.getExactDifference(seedDate, repaymentPeriod.getFromDate(), chronoUnit);
                }
            }
            default -> throw new UnsupportedOperationException("Unsupported chrono unit: " + chronoUnit);
        };

        int multiplicator = numberOfPeriodBetweenSeedDateAndActualRepaymentPeriod + 1;
        LocalDate fromDate = repaymentPeriod.getFromDate();
        while (fromDate.isBefore(repaymentPeriod.getDueDate())) {
            fromDate = seedDate.plus(multiplicator, chronoUnit);
            if (!fromDate.isAfter(repaymentPeriod.getDueDate())) {
                multiplicator++;
            } else {
                LocalDate fullPeriodDate = fromDate;
                multiplicator = multiplicator - numberOfPeriodBetweenSeedDateAndActualRepaymentPeriod - 1;
                fromDate = seedDate.plus(multiplicator, chronoUnit);
                final long differenceInDays = DateUtils.getDifferenceInDays(fromDate, repaymentPeriod.getDueDate());
                final long fullPeriodDifferenceInDays = DateUtils.getDifferenceInDays(fromDate, fullPeriodDate);
                return BigDecimal.valueOf(differenceInDays).divide(BigDecimal.valueOf(fullPeriodDifferenceInDays), mc)
                        .add(BigDecimal.valueOf(multiplicator));
            }
        }
        multiplicator = multiplicator - numberOfPeriodBetweenSeedDateAndActualRepaymentPeriod - 1;
        return BigDecimal.valueOf(multiplicator);
    }

    private static LocalDate calculateSeedDate(ProgressiveLoanInterestScheduleModel scheduleModel, RepaymentPeriod repaymentPeriod) {
        LocalDate seedDate = scheduleModel.getStartDate();
        LocalDate calculatedDate;
        int multiplicator = 1;
        ChronoUnit chronoUnit = switch (scheduleModel.loanProductRelatedDetail().getRepaymentPeriodFrequencyType()) {
            case YEARS -> ChronoUnit.YEARS;
            case MONTHS -> ChronoUnit.MONTHS;
            case WEEKS -> ChronoUnit.WEEKS;
            case DAYS -> ChronoUnit.DAYS;
            default -> throw new UnsupportedOperationException(
                    "Unsupported repayment frequency: " + scheduleModel.loanProductRelatedDetail().getRepaymentPeriodFrequencyType());
        };
        do {
            calculatedDate = seedDate.plus(multiplicator, chronoUnit);
            multiplicator++;
        } while (calculatedDate.isBefore(repaymentPeriod.getDueDate()));
        return calculatedDate.equals(repaymentPeriod.getDueDate()) ? seedDate : repaymentPeriod.getFromDate();
    }

    /**
     * Calculate Rate Factor for an exact Period
     */
    private BigDecimal calculateRateFactorPerPeriod(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final RepaymentPeriod repaymentPeriod, final LocalDate interestPeriodFromDate, final LocalDate interestPeriodDueDate) {
        final MathContext mc = scheduleModel.mc();
        final LoanProductMinimumRepaymentScheduleRelatedDetail loanProductRelatedDetail = scheduleModel.loanProductRelatedDetail();
        final BigDecimal interestRate = calcNominalInterestRatePercentage(scheduleModel.getInterestRate(interestPeriodFromDate),
                scheduleModel.mc());
        final DaysInYearType daysInYearType = DaysInYearType.fromInt(loanProductRelatedDetail.getDaysInYearType());
        final DaysInMonthType daysInMonthType = DaysInMonthType.fromInt(loanProductRelatedDetail.getDaysInMonthType());
        final PeriodFrequencyType repaymentFrequency = loanProductRelatedDetail.getRepaymentPeriodFrequencyType();
        final BigDecimal repaymentEvery = BigDecimal.valueOf(loanProductRelatedDetail.getRepayEvery());

        BigDecimal daysInYear = BigDecimal.valueOf(daysInYearType.getNumberOfDays(interestPeriodFromDate));
        final BigDecimal actualDaysInPeriod = BigDecimal
                .valueOf(DateUtils.getDifferenceInDays(interestPeriodFromDate, interestPeriodDueDate));
        final BigDecimal calculatedDaysInPeriod = BigDecimal
                .valueOf(DateUtils.getDifferenceInDays(repaymentPeriod.getFromDate(), repaymentPeriod.getDueDate()));
        final int numberOfYearsDifferenceInPeriod = interestPeriodDueDate.getYear() - interestPeriodFromDate.getYear();
        final boolean partialPeriodCalculationNeeded = daysInYearType == DaysInYearType.ACTUAL && numberOfYearsDifferenceInPeriod > 0;
        final BigDecimal daysInMonth = daysInMonthType.isDaysInMonth_30() ? BigDecimal.valueOf(30) : calculatedDaysInPeriod;

        // TODO check: loanApplicationTerms.calculatePeriodsBetweenDates(startDate, endDate); // calculate period data
        // TODO review: (repayment frequency: days, weeks, years; validation day is month fix 30)
        // TODO refactor this logic to represent in interest period
        if (partialPeriodCalculationNeeded) {
            final BigDecimal cumulatedPeriodFractions = calculatePeriodFractions(scheduleModel, interestPeriodFromDate,
                    interestPeriodDueDate, mc);
            return rateFactorByRepaymentPartialPeriod(interestRate, BigDecimal.ONE, cumulatedPeriodFractions, BigDecimal.ONE,
                    BigDecimal.ONE, mc);
        }

        return switch (daysInMonthType) {
            case ACTUAL -> rateFactorByRepaymentPeriod(interestRate, actualDaysInPeriod, BigDecimal.ONE, daysInYear, BigDecimal.ONE,
                    BigDecimal.ONE, mc);
            case DAYS_30 -> calculateRateFactorPerPeriodBasedOnRepaymentFrequency(interestRate, repaymentFrequency, repaymentEvery,
                    daysInMonth, daysInYear, actualDaysInPeriod, calculatedDaysInPeriod, mc);
            default -> throw new UnsupportedOperationException("Unsupported combination: Days in month: " + daysInMonthType);
        };
    }

    /**
     * Calculate Period fractions part based on how much year has in the period
     *
     * @param scheduleModel
     * @param interestPeriodFromDate
     * @param interestPeriodDueDate
     * @return
     */
    public BigDecimal calculatePeriodFractions(ProgressiveLoanInterestScheduleModel scheduleModel, final LocalDate interestPeriodFromDate,
            final LocalDate interestPeriodDueDate, MathContext mc) {
        BigDecimal cumulatedRateFactor = BigDecimal.ZERO;
        int actualYear = interestPeriodFromDate.getYear();
        int endYear = interestPeriodDueDate.getYear();
        LocalDate actualDate = interestPeriodFromDate;
        LocalDate fractionPeriodDueDate;

        while (actualYear <= endYear) {
            fractionPeriodDueDate = actualYear == endYear ? interestPeriodDueDate
                    : getFractionPeriodDueDateForEndOfYear(scheduleModel, actualYear);
            BigDecimal numberOfDaysInYear = BigDecimal.valueOf(Year.of(actualYear).length());
            BigDecimal calculatedDaysInActualYear = BigDecimal.valueOf(DateUtils.getDifferenceInDays(actualDate, fractionPeriodDueDate));
            cumulatedRateFactor = cumulatedRateFactor.add(calculatedDaysInActualYear.divide(numberOfDaysInYear, mc), mc);
            actualDate = fractionPeriodDueDate;
            actualYear++;
        }
        return cumulatedRateFactor;
    }

    /**
     * Determines the last date of the year for interest calculation depending on the
     * isInterestRecognitionOnDisbursementDate flag.
     *
     * @param scheduleModel
     * @param year
     * @return
     */
    private LocalDate getFractionPeriodDueDateForEndOfYear(ProgressiveLoanInterestScheduleModel scheduleModel, int year) {
        if (scheduleModel.loanProductRelatedDetail().isInterestRecognitionOnDisbursementDate()) {
            return LocalDate.of(year + 1, 1, 1);
        } else {
            return LocalDate.of(year, 12, 31);
        }
    }

    /**
     * Calculate Rate Factor based on Repayment Frequency Type
     *
     * @param interestRate
     * @param repaymentFrequency
     * @param repaymentEvery
     * @param daysInMonth
     * @param daysInYear
     * @param actualDaysInPeriod
     * @param calculatedDaysInPeriod
     * @return
     */
    private BigDecimal calculateRateFactorPerPeriodBasedOnRepaymentFrequency(final BigDecimal interestRate,
            final PeriodFrequencyType repaymentFrequency, final BigDecimal repaymentEvery, final BigDecimal daysInMonth,
            final BigDecimal daysInYear, final BigDecimal actualDaysInPeriod, final BigDecimal calculatedDaysInPeriod,
            final MathContext mc) {
        return switch (repaymentFrequency) {
            case DAYS ->
                rateFactorByRepaymentEveryDay(interestRate, repaymentEvery, daysInYear, actualDaysInPeriod, calculatedDaysInPeriod, mc);
            case WEEKS ->
                rateFactorByRepaymentEveryWeek(interestRate, repaymentEvery, daysInYear, actualDaysInPeriod, calculatedDaysInPeriod, mc);
            case MONTHS -> rateFactorByRepaymentEveryMonth(interestRate, repaymentEvery, daysInMonth, daysInYear, actualDaysInPeriod,
                    calculatedDaysInPeriod, mc);
            default -> throw new UnsupportedOperationException("Invalid repayment frequency"); // not supported yet
        };
    }

    private void calculateEMIOnActualModel(List<RepaymentPeriod> repaymentPeriods, ProgressiveLoanInterestScheduleModel scheduleModel) {
        if (repaymentPeriods.isEmpty()) {
            return;
        }
        final MathContext mc = scheduleModel.mc();
        final BigDecimal rateFactorN = MathUtil.stripTrailingZeros(calculateRateFactorPlus1N(repaymentPeriods, mc));
        final BigDecimal fnResult = MathUtil.stripTrailingZeros(calculateFnResult(repaymentPeriods, mc));
        final RepaymentPeriod startPeriod = repaymentPeriods.get(0);

        final Money outstandingBalance = startPeriod.getInitialBalanceForEmiRecalculation();

        final Money equalMonthlyInstallment = Money.of(outstandingBalance.getCurrencyData(),
                calculateEMIValue(rateFactorN, outstandingBalance.getAmount(), fnResult, mc), mc);
        final Money finalEqualMonthlyInstallment = applyInstallmentAmountInMultiplesOf(scheduleModel, equalMonthlyInstallment);

        repaymentPeriods.forEach(period -> {
            if (!finalEqualMonthlyInstallment.isLessThan(period.getTotalPaidAmount())) {
                period.setEmi(finalEqualMonthlyInstallment);
                period.setOriginalEmi(finalEqualMonthlyInstallment);
            }
        });
    }

    private void calculateEMIOnNewModelAndMerge(List<RepaymentPeriod> repaymentPeriods, ProgressiveLoanInterestScheduleModel scheduleModel,
            final EmiChangeOperation operation) {
        if (repaymentPeriods.isEmpty()) {
            return;
        }
        final ProgressiveLoanInterestScheduleModel scheduleModelCopy = scheduleModel.copyWithoutPaidAmounts();
        addDisbursement(scheduleModelCopy, operation.withZeroAmount());

        final LocalDate firstDueDate = repaymentPeriods.get(0).getDueDate();
        scheduleModel.copyPeriodsFrom(firstDueDate, scheduleModelCopy.repaymentPeriods(), (newRepaymentPeriod, actualRepaymentPeriod) -> {
            actualRepaymentPeriod.setEmi(newRepaymentPeriod.getEmi());
            actualRepaymentPeriod.setOriginalEmi(newRepaymentPeriod.getOriginalEmi());
        });
    }

    private Money applyInstallmentAmountInMultiplesOf(final ProgressiveLoanInterestScheduleModel scheduleModel,
            final Money equalMonthlyInstallment) {
        return scheduleModel.installmentAmountInMultiplesOf() != null
                ? Money.roundToMultiplesOf(equalMonthlyInstallment, scheduleModel.installmentAmountInMultiplesOf())
                : equalMonthlyInstallment;
    }

    public EmiAdjustment getEmiAdjustment(final List<RepaymentPeriod> repaymentPeriods) {
        for (int idx = repaymentPeriods.size() - 1; idx > 0; --idx) {
            RepaymentPeriod lastPeriod = repaymentPeriods.get(idx);
            RepaymentPeriod penultimatePeriod = repaymentPeriods.get(idx - 1);
            if (!lastPeriod.isFullyPaid() && !penultimatePeriod.isFullyPaid()) {
                Money emiDifference = lastPeriod.getEmi().minus(penultimatePeriod.getEmi());
                return new EmiAdjustment(penultimatePeriod.getEmi(), emiDifference, repaymentPeriods,
                        getUncountablePeriods(repaymentPeriods, penultimatePeriod.getEmi()));
            }
        }
        return new EmiAdjustment(repaymentPeriods.get(0).getEmi(), repaymentPeriods.get(0).getEmi().copy(0.0), repaymentPeriods, 0);
    }

    /**
     * Calculate Rate Factor Product from rate factors
     */
    private BigDecimal calculateRateFactorPlus1N(final List<RepaymentPeriod> periods, MathContext mc) {
        return periods.stream().map(RepaymentPeriod::getRateFactorPlus1).reduce(BigDecimal.ONE,
                (BigDecimal acc, BigDecimal value) -> acc.multiply(value, mc));
    }

    /**
     * Summarize Fn values
     */
    private BigDecimal calculateFnResult(final List<RepaymentPeriod> periods, final MathContext mc) {
        return periods.stream()//
                .skip(1)//
                .map(RepaymentPeriod::getRateFactorPlus1)//
                .reduce(BigDecimal.ONE, (previousFnValue, currentRateFactor) -> fnValue(previousFnValue, currentRateFactor, mc));//
    }

    /**
     * Calculate the EMI (Equal Monthly Installment) value
     */
    private BigDecimal calculateEMIValue(final BigDecimal rateFactorPlus1N, final BigDecimal outstandingBalanceForRest,
            final BigDecimal fnResult, MathContext mc) {
        return rateFactorPlus1N.multiply(outstandingBalanceForRest, mc).divide(fnResult, mc);
    }

    /**
     * To calculate the daily payment, we first need to calculate something called the Rate Factor. We're going to be
     * using simple interest. The Rate Factor for simple interest is calculated by the following formula:
     *
     * Rate factor = 1 + (rate of interest * (repaid every / days in year) * actual days in period / calculated days in
     * period ) Where
     *
     * @param interestRate
     *            Rate of Interest
     *
     * @param repaymentEvery
     *            Repaid Every
     *
     * @param daysInYear
     *            Days is Year based on DaysInYear enum
     *
     * @param actualDaysInPeriod
     *            Always the actual number of days in the actual period
     *
     * @param calculatedDaysInPeriod
     *            Calculated days in Period (It has importance related to Reschedule)
     *
     * @return Rate Factor for period
     */
    private BigDecimal rateFactorByRepaymentEveryDay(final BigDecimal interestRate, final BigDecimal repaymentEvery,
            final BigDecimal daysInYear, final BigDecimal actualDaysInPeriod, final BigDecimal calculatedDaysInPeriod, MathContext mc) {
        return rateFactorByRepaymentPeriod(interestRate, BigDecimal.ONE, repaymentEvery, daysInYear, actualDaysInPeriod,
                calculatedDaysInPeriod, mc);
    }

    /**
     * To calculate the weekly payment, we first need to calculate something called the Rate Factor. We're going to be
     * using simple interest. The Rate Factor for simple interest is calculated by the following formula:
     *
     * Rate factor = 1 + (rate of interest * (7 * repaid every / days in year) * actual days in period / calculated days
     * in period ) Where
     *
     * @param interestRate
     *            Rate of Interest
     *
     * @param repaymentEvery
     *            Repaid Every
     *
     * @param daysInYear
     *            Days is Year based on DaysInYear enum
     *
     * @param actualDaysInPeriod
     *            Always the actual number of days in the actual period
     *
     * @param calculatedDaysInPeriod
     *            Calculated days in Period (It has importance related to Reschedule)
     *
     * @return Rate Factor for period
     */
    private BigDecimal rateFactorByRepaymentEveryWeek(final BigDecimal interestRate, final BigDecimal repaymentEvery,
            final BigDecimal daysInYear, final BigDecimal actualDaysInPeriod, final BigDecimal calculatedDaysInPeriod, MathContext mc) {
        return rateFactorByRepaymentPeriod(interestRate, ONE_WEEK_IN_DAYS, repaymentEvery, daysInYear, actualDaysInPeriod,
                calculatedDaysInPeriod, mc);
    }

    /**
     * To calculate the monthly payment, we first need to calculate something called the Rate Factor. We're going to be
     * using simple interest. The Rate Factor for simple interest is calculated by the following formula:
     *
     * Rate factor = 1 + (rate of interest * (days in month * repaid every / days in year) * actual days in period /
     * calculated days in period ) Where
     *
     * @param interestRate
     *            Rate of Interest
     *
     * @param repaymentEvery
     *            Repaid Every
     *
     * @param daysInMonth
     *            Days in Month based on DaysInMonth enum
     *
     * @param daysInYear
     *            Days is Year based on DaysInYear enum
     *
     * @param actualDaysInPeriod
     *            Always the actual number of days in the actual period
     *
     * @param calculatedDaysInPeriod
     *            Calculated days in Period (It has importance related to Reschedule)
     *
     * @return Rate Factor for period
     */
    BigDecimal rateFactorByRepaymentEveryMonth(final BigDecimal interestRate, final BigDecimal repaymentEvery, final BigDecimal daysInMonth,
            final BigDecimal daysInYear, final BigDecimal actualDaysInPeriod, final BigDecimal calculatedDaysInPeriod,
            final MathContext mc) {
        return rateFactorByRepaymentPeriod(interestRate, daysInMonth, repaymentEvery, daysInYear, actualDaysInPeriod,
                calculatedDaysInPeriod, mc);
    }

    /**
     * To calculate installment period payment. We're going to be using simple interest. The Rate Factor for simple
     * interest is calculated by the following formula:
     *
     * Rate factor = 1 + (rate of interest * ( repayment period multiplier in days * repaid every * days in month / days
     * in year) * actual days in period / calculated days in period ) Where
     *
     * @param interestRate
     *            Rate of Interest
     *
     * @param repaymentPeriodMultiplierInDays
     *            Multiplier number in days of the repayment every parameter
     *
     * @param repaymentEvery
     *            Repaid Every
     *
     * @param daysInYear
     *            Days is Year based on DaysInYear enum
     *
     * @param actualDaysInPeriod
     *            Always the actual number of days in the actual period
     *
     * @param calculatedDaysInPeriod
     *            Calculated days in Period (It has importance related to Reschedule)
     *
     * @return Rate Factor for period
     */
    private BigDecimal rateFactorByRepaymentPeriod(final BigDecimal interestRate, final BigDecimal repaymentPeriodMultiplierInDays,
            final BigDecimal repaymentEvery, final BigDecimal daysInYear, final BigDecimal actualDaysInPeriod,
            final BigDecimal calculatedDaysInPeriod, final MathContext mc) {
        if (MathUtil.isZero(calculatedDaysInPeriod)) {
            return BigDecimal.ZERO;
        }
        final BigDecimal interestFractionPerPeriod = repaymentPeriodMultiplierInDays//
                .multiply(repaymentEvery, mc)//
                .divide(daysInYear, mc);//
        return interestRate//
                .multiply(interestFractionPerPeriod, mc)//
                .multiply(actualDaysInPeriod, mc)//
                .divide(calculatedDaysInPeriod, mc).setScale(mc.getPrecision(), mc.getRoundingMode());//
    }

    /**
     * Calculate Rate Factor based on Partial Period
     *
     */
    private BigDecimal rateFactorByRepaymentPartialPeriod(final BigDecimal interestRate, final BigDecimal repaymentEvery,
            final BigDecimal cumulatedPeriodRatio, final BigDecimal actualDaysInPeriod, final BigDecimal calculatedDaysInPeriod,
            final MathContext mc) {
        if (MathUtil.isZero(calculatedDaysInPeriod)) {
            return BigDecimal.ZERO;
        }
        final BigDecimal interestFractionPerPeriod = repaymentEvery.multiply(cumulatedPeriodRatio);
        return interestRate//
                .multiply(interestFractionPerPeriod, mc)//
                .multiply(actualDaysInPeriod, mc)//
                .divide(calculatedDaysInPeriod, mc).setScale(mc.getPrecision(), mc.getRoundingMode());//
    }

    /**
     * To calculate the function value for each period, we are going to use the next formula:
     *
     * fn = 1 + fnValueFrom * rateFactorEnd
     *
     * @param previousFnValue
     *
     * @param currentRateFactor
     *
     */
    BigDecimal fnValue(final BigDecimal previousFnValue, final BigDecimal currentRateFactor, final MathContext mc) {
        return BigDecimal.ONE.add(previousFnValue.multiply(currentRateFactor, mc), mc);
    }

    /**
     * Calculates the sum of due interests on interest periods.
     *
     * @param scheduleModel
     *            schedule model
     * @param subjectDate
     *            the date to calculate the interest for.
     * @return sum of due interests
     */
    @Override
    public Money getSumOfDueInterestsOnDate(ProgressiveLoanInterestScheduleModel scheduleModel, LocalDate subjectDate) {
        return scheduleModel.repaymentPeriods().stream().map(RepaymentPeriod::getDueDate) //
                .map(repaymentPeriodDueDate -> getDueAmounts(scheduleModel, repaymentPeriodDueDate, subjectDate) //
                        .getDueInterest()) //
                .reduce(scheduleModel.zero(), Money::add); //
    }

    @Override
    public void applyInterestPause(final ProgressiveLoanInterestScheduleModel scheduleModel, final LocalDate fromDate,
            final LocalDate endDate) {
        scheduleModel.updateInterestPeriodsForInterestPause(fromDate, endDate)
                .ifPresent(repaymentPeriod -> calculateRateFactorsForInterestPause(scheduleModel, repaymentPeriod.getFromDate()));
    }

    private void calculateRateFactorsForInterestPause(final ProgressiveLoanInterestScheduleModel scheduleModel, final LocalDate startDate) {
        final List<RepaymentPeriod> relatedRepaymentPeriods = scheduleModel.getRelatedRepaymentPeriods(startDate);
        calculateRateFactorForPeriods(relatedRepaymentPeriods, scheduleModel);
        calculateOutstandingBalance(scheduleModel);
        calculateLastUnpaidRepaymentPeriodEMI(scheduleModel);
    }

    private long getUncountablePeriods(final List<RepaymentPeriod> relatedRepaymentPeriods, final Money originalEmi) {
        return relatedRepaymentPeriods.stream() //
                .filter(repaymentPeriod -> originalEmi.isLessThan(repaymentPeriod.getTotalPaidAmount())) //
                .count(); //
    }
}
