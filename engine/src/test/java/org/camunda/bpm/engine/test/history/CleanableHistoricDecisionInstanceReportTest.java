/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.test.history;

import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.exception.NotValidException;
import org.camunda.bpm.engine.history.CleanableHistoricDecisionInstanceReport;
import org.camunda.bpm.engine.history.CleanableHistoricDecisionInstanceReportResult;
import org.camunda.bpm.engine.history.HistoricDecisionInstance;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.repository.DecisionDefinition;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class CleanableHistoricDecisionInstanceReportTest {
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(testRule).around(engineRule);

  protected HistoryService historyService;
  protected RepositoryService repositoryService;

  @Parameterized.Parameter
  public boolean isHierarchicalCleanup;
  protected boolean isHierarchicalCleanupInitValue;

  protected static final String DECISION_DEFINITION_KEY = "one";
  protected static final String SECOND_DECISION_DEFINITION_KEY = "two";
  protected static final String THIRD_DECISION_DEFINITION_KEY = "anotherDecision";
  protected static final String FOURTH_DECISION_DEFINITION_KEY = "decision";
  protected static final String ROOT_DMN_DEFINITION_KEY = "dish-decision";
  protected static final String DRG_DMN = "org/camunda/bpm/engine/test/dmn/deployment/drdMultiLevelDish.dmn11.xml";

  @Parameterized.Parameters(name = "Hierachical History Cleanup: {0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{true}, {false}});
  }

  @Before
  public void setUp() {
    historyService = engineRule.getHistoryService();
    repositoryService = engineRule.getRepositoryService();

    testRule.deploy("org/camunda/bpm/engine/test/repository/one.dmn");

    isHierarchicalCleanupInitValue = engineRule.getProcessEngineConfiguration().isHierarchicalHistoryCleanup();
    engineRule.getProcessEngineConfiguration().setHierarchicalHistoryCleanup(isHierarchicalCleanup);
  }

  @After
  public void cleanUp() {

    List<HistoricDecisionInstance> historicDecisionInstances = historyService.createHistoricDecisionInstanceQuery().list();
    for (HistoricDecisionInstance historicDecisionInstance : historicDecisionInstances) {
      historyService.deleteHistoricDecisionInstanceByInstanceId(historicDecisionInstance.getId());
    }

    engineRule.getProcessEngineConfiguration().setHierarchicalHistoryCleanup(isHierarchicalCleanupInitValue);
  }

  protected void prepareDecisionInstances(String key, int daysInThePast, Integer historyTimeToLive, int instanceCount) {
    prepareDecisionInstances(key, daysInThePast, historyTimeToLive, instanceCount, null);
  }

  protected void prepareDecisionInstances(String key, int daysInThePast, Integer historyTimeToLive, int instanceCount, VariableMap variables) {
    List<DecisionDefinition> decisionDefinitions = repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(key).list();
    assertEquals(1, decisionDefinitions.size());
    repositoryService.updateDecisionDefinitionHistoryTimeToLive(decisionDefinitions.get(0).getId(), historyTimeToLive);

    Date oldCurrentTime = ClockUtil.getCurrentTime();
    ClockUtil.setCurrentTime(DateUtils.addDays(oldCurrentTime, daysInThePast));

    variables = (variables != null)? variables : Variables.createVariables().putValue("status", "silver").putValue("sum", 723);
    for (int i = 0; i < instanceCount; i++) {
      engineRule.getDecisionService().evaluateDecisionByKey(key).variables(variables).evaluate();
    }

    ClockUtil.setCurrentTime(oldCurrentTime);
  }

  @Test
  public void testReportComplex() {
    // given
    testRule.deploy("org/camunda/bpm/engine/test/repository/two.dmn", "org/camunda/bpm/engine/test/api/dmn/Another_Example.dmn",
        "org/camunda/bpm/engine/test/api/dmn/Example.dmn");
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 5, 10);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 10);
    prepareDecisionInstances(SECOND_DECISION_DEFINITION_KEY, -6, null, 10);
    prepareDecisionInstances(THIRD_DECISION_DEFINITION_KEY, -6, 5, 10);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResults = historyService.createCleanableHistoricDecisionInstanceReport().list();
    String secondDecisionDefinitionId = repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(SECOND_DECISION_DEFINITION_KEY).singleResult().getId();
    CleanableHistoricDecisionInstanceReportResult secondReportResult = historyService.createCleanableHistoricDecisionInstanceReport().decisionDefinitionIdIn(secondDecisionDefinitionId).singleResult();
    CleanableHistoricDecisionInstanceReportResult thirdReportResult = historyService.createCleanableHistoricDecisionInstanceReport().decisionDefinitionKeyIn(THIRD_DECISION_DEFINITION_KEY).singleResult();

    // then
    assertEquals(4, reportResults.size());
    for (CleanableHistoricDecisionInstanceReportResult result : reportResults) {
      if (result.getDecisionDefinitionKey().equals(DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 10, 20);
      } else if (result.getDecisionDefinitionKey().equals(SECOND_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 0, 10);
      } else if (result.getDecisionDefinitionKey().equals(THIRD_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 10, 10);
      } else if (result.getDecisionDefinitionKey().equals(FOURTH_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 0, 0);
      }
    }
    checkResultNumbers(secondReportResult, 0, 10);
    checkResultNumbers(thirdReportResult, 10, 10);

  }

  private void checkResultNumbers(CleanableHistoricDecisionInstanceReportResult result, int expectedCleanable, int expectedFinished) {
    assertEquals(expectedCleanable, result.getCleanableDecisionInstanceCount());
    assertEquals(expectedFinished, result.getFinishedDecisionInstanceCount());
  }

  @Test
  public void testReportWithAllCleanableInstances() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 10);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResults = historyService.createCleanableHistoricDecisionInstanceReport().list();
    long count = historyService.createCleanableHistoricDecisionInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    assertEquals(1, count);

    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testReportWithPartiallyCleanableInstances() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 5, 5);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResults = historyService.createCleanableHistoricDecisionInstanceReport().list();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 5, 10);
  }

  @Test
  public void testReportWithZeroHistoryTTL() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 0, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 0, 5);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResults = historyService.createCleanableHistoricDecisionInstanceReport().list();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testReportWithNullHistoryTTL() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, null, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, null, 5);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResults = historyService.createCleanableHistoricDecisionInstanceReport().list();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 0, 10);
  }

  @Test
  public void testReportByInvalidDecisionDefinitionId() {
    CleanableHistoricDecisionInstanceReport report = historyService.createCleanableHistoricDecisionInstanceReport();

    try {
      report.decisionDefinitionIdIn(null);
      fail("Expected NotValidException");
    } catch (NotValidException e) {
      // expected
    }

    try {
      report.decisionDefinitionIdIn("abc", null, "def");
      fail("Expected NotValidException");
    } catch (NotValidException e) {
      // expected
    }
  }

  @Test
  public void testReportByInvalidDecisionDefinitionKey() {
    CleanableHistoricDecisionInstanceReport report = historyService.createCleanableHistoricDecisionInstanceReport();

    try {
      report.decisionDefinitionKeyIn(null);
      fail("Expected NotValidException");
    } catch (NotValidException e) {
      // expected
    }

    try {
      report.decisionDefinitionKeyIn("abc", null, "def");
      fail("Expected NotValidException");
    } catch (NotValidException e) {
      // expected
    }
  }

  @Test
  public void testReportCompact() {
    // given
    List<DecisionDefinition> decisionDefinitions = repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(DECISION_DEFINITION_KEY).list();
    assertEquals(1, decisionDefinitions.size());

    // assume
    List<CleanableHistoricDecisionInstanceReportResult> resultWithZeros = historyService.createCleanableHistoricDecisionInstanceReport().list();
    assertEquals(1, resultWithZeros.size());
    assertEquals(0, resultWithZeros.get(0).getFinishedDecisionInstanceCount());

    // when
    long resultCountWithoutZeros = historyService.createCleanableHistoricDecisionInstanceReport().compact().count();

    // then
    assertEquals(0, resultCountWithoutZeros);
  }

  @Test
  public void testReportOrderByFinishedAsc() {
    // give
    testRule.deploy("org/camunda/bpm/engine/test/repository/two.dmn", "org/camunda/bpm/engine/test/api/dmn/Another_Example.dmn");
    prepareDecisionInstances(SECOND_DECISION_DEFINITION_KEY, -6, 5, 6);
    prepareDecisionInstances(THIRD_DECISION_DEFINITION_KEY, -6, 5, 8);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 4);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResult = historyService
        .createCleanableHistoricDecisionInstanceReport()
        .orderByFinished()
        .asc()
        .list();

    // then
    assertEquals(3, reportResult.size());
    assertEquals(DECISION_DEFINITION_KEY, reportResult.get(0).getDecisionDefinitionKey());
    assertEquals(SECOND_DECISION_DEFINITION_KEY, reportResult.get(1).getDecisionDefinitionKey());
    assertEquals(THIRD_DECISION_DEFINITION_KEY, reportResult.get(2).getDecisionDefinitionKey());
  }

  @Test
  public void testReportOrderByFinishedDesc() {
    // give
    testRule.deploy("org/camunda/bpm/engine/test/repository/two.dmn", "org/camunda/bpm/engine/test/api/dmn/Another_Example.dmn");
    prepareDecisionInstances(SECOND_DECISION_DEFINITION_KEY, -6, 5, 6);
    prepareDecisionInstances(THIRD_DECISION_DEFINITION_KEY, -6, 5, 8);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 4);

    // when
    List<CleanableHistoricDecisionInstanceReportResult> reportResult = historyService
        .createCleanableHistoricDecisionInstanceReport()
        .orderByFinished()
        .desc()
        .list();

    // then
    assertEquals(3, reportResult.size());
    assertEquals(THIRD_DECISION_DEFINITION_KEY, reportResult.get(0).getDecisionDefinitionKey());
    assertEquals(SECOND_DECISION_DEFINITION_KEY, reportResult.get(1).getDecisionDefinitionKey());
    assertEquals(DECISION_DEFINITION_KEY, reportResult.get(2).getDecisionDefinitionKey());
  }

  @Test
  public void testReportOnHierarchicalData(){
    testRule.deploy(DRG_DMN);

    updateDecisionDefinitionHistoryTTL("feels", 5);
    updateDecisionDefinitionHistoryTTL("guestCount", 5);

    prepareDecisionInstances(ROOT_DMN_DEFINITION_KEY, -3, 2, 1,
      Variables.createVariables().putValue("temperature", 21).putValue("dayType", "WeekDay"));

    List<CleanableHistoricDecisionInstanceReportResult> decisionReportResult = historyService
      .createCleanableHistoricDecisionInstanceReport()
      .decisionDefinitionKeyIn("guestCount", "feels", "dish-decision")
      .list();

    // then
    assertEquals(3, decisionReportResult.size());
  }

  private void updateDecisionDefinitionHistoryTTL(String decisionDefinitionKey, int newHistoryTimeToLive) {
    String definitionId = repositoryService.createDecisionDefinitionQuery()
      .decisionDefinitionKey(decisionDefinitionKey)
      .singleResult()
      .getId();
    repositoryService.updateDecisionDefinitionHistoryTimeToLive(definitionId, newHistoryTimeToLive);
  }
}
