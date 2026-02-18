package com.flik.common.constants;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CostConstantsTest {

    @Test
    void costForTaskType_returnsCorrectCosts() {
        assertEquals(0.001, CostConstants.costForTaskType("TEXT"));
        assertEquals(0.01, CostConstants.costForTaskType("IMAGE"));
        assertEquals(0.10, CostConstants.costForTaskType("VIDEO"));
    }

    @Test
    void costForTaskType_isCaseInsensitive() {
        assertEquals(0.001, CostConstants.costForTaskType("text"));
        assertEquals(0.01, CostConstants.costForTaskType("Image"));
    }

    @Test
    void costForTaskType_unknownTypeDefaultsToMinCost() {
        assertEquals(0.001, CostConstants.costForTaskType("UNKNOWN"));
    }

    @Test
    void workerCostPerHour_returnsCorrectCosts() {
        assertEquals(0.50, CostConstants.workerCostPerHour("TEXT"));
        assertEquals(2.00, CostConstants.workerCostPerHour("IMAGE"));
        assertEquals(8.00, CostConstants.workerCostPerHour("VIDEO"));
    }

    @Test
    void workerCostPerHour_unknownTypeDefaultsToMinCost() {
        assertEquals(0.50, CostConstants.workerCostPerHour("UNKNOWN"));
    }

    @Test
    void defaultBudgetPerHour_isFifty() {
        assertEquals(50.0, CostConstants.DEFAULT_BUDGET_PER_HOUR);
    }
}
