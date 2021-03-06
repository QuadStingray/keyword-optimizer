// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.ads.adwords.keywordoptimizer;

import com.google.api.ads.adwords.axis.v201809.cm.ApiException;
import com.google.api.ads.adwords.axis.v201809.cm.Keyword;
import com.google.api.ads.adwords.axis.v201809.cm.KeywordMatchType;
import com.google.api.ads.adwords.axis.v201809.cm.Paging;
import com.google.api.ads.adwords.axis.v201809.o.Attribute;
import com.google.api.ads.adwords.axis.v201809.o.AttributeType;
import com.google.api.ads.adwords.axis.v201809.o.IdeaType;
import com.google.api.ads.adwords.axis.v201809.o.RelatedToQuerySearchParameter;
import com.google.api.ads.adwords.axis.v201809.o.RequestType;
import com.google.api.ads.adwords.axis.v201809.o.SearchParameter;
import com.google.api.ads.adwords.axis.v201809.o.StringAttribute;
import com.google.api.ads.adwords.axis.v201809.o.TargetingIdea;
import com.google.api.ads.adwords.axis.v201809.o.TargetingIdeaPage;
import com.google.api.ads.adwords.axis.v201809.o.TargetingIdeaSelector;
import com.google.api.ads.adwords.axis.v201809.o.TargetingIdeaService;
import com.google.api.ads.adwords.axis.v201809.o.TargetingIdeaServiceInterface;
import com.google.api.ads.common.lib.utils.Maps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses the {@link TargetingIdeaService} to create new keyword alternatives. This works pretty much
 * the same way as the {@link TisSearchTermsSeedGenerator}, meaning it creates keywords based on a
 * given set of already existing ones.
 */
public class TisAlternativesFinder implements AlternativesFinder {
  // Page size for retrieving results. All pages are used anyways (not just the first one), so
  // using a reasonable value here.
  private static final int PAGE_SIZE = 100;

  private TargetingIdeaServiceInterface tis;

  /**
   * Creates a new {@link TisAlternativesFinder}.
   *
   * @param context holding shared objects during the optimization process
   */
  public TisAlternativesFinder(OptimizationContext context) {
    tis = context.getAdwordsApiUtil().getService(TargetingIdeaServiceInterface.class);
  }

  @Override
  public KeywordCollection derive(KeywordCollection keywords) throws KeywordOptimizerException {
    Map<String, IdeaEstimate> keywordsAndEstimates = getKeywordsAndEstimates(keywords);

    KeywordCollection alternatives = new KeywordCollection(keywords.getCampaignConfiguration());
    for (String keywordText : keywordsAndEstimates.keySet()) {
      for (KeywordMatchType matchType : keywords.getContainingMatchTypes()) {
        Keyword newKeyword = KeywordOptimizerUtil.createKeyword(keywordText, matchType);
        alternatives.add(
            new KeywordInfo(newKeyword, keywordsAndEstimates.get(keywordText), null, null));
      }
    }

    return alternatives;
  }

  /**
   * Creates the selector for the {@link TargetingIdeaService} based on a given set of {@link
   * KeywordCollection}.
   *
   * @param keywords the {@link KeywordCollection} to create the selector
   * @return the selector for the {@link TargetingIdeaService}
   */
  private TargetingIdeaSelector getSelector(KeywordCollection keywords) {
    TargetingIdeaSelector selector = new TargetingIdeaSelector();
    selector.setRequestType(RequestType.IDEAS);
    selector.setIdeaType(IdeaType.KEYWORD);
    selector.setRequestedAttributeTypes(KeywordOptimizerUtil.TIS_ATTRIBUTE_TYPES);

    List<SearchParameter> searchParameters = new ArrayList<>();

    // Get ideas related to query search parameter.
    RelatedToQuerySearchParameter relatedToQuerySearchParameter =
        new RelatedToQuerySearchParameter();
    relatedToQuerySearchParameter.setQueries(keywords.getContainingKeywordTexts().toArray(
        new String[] {}));
    searchParameters.add(relatedToQuerySearchParameter);

    // Now add all other criteria.
    searchParameters.addAll(
        KeywordOptimizerUtil.toSearchParameters(
            keywords.getCampaignConfiguration().getAdditionalCriteria()));

    selector.setSearchParameters(searchParameters.toArray(new SearchParameter[] {}));

    return selector;
  }

  /**
   * Finds a collection of plain text keywords based on the given set of keywords.
   *
   * @param keywords the keywords to as a basis for finding new ones
   * @return a {@link Map} of plain text keywords and their {@link IdeaEstimate}s
   * @throws KeywordOptimizerException in case of an error retrieving keywords from TIS
   */
  private ImmutableMap<String, IdeaEstimate> getKeywordsAndEstimates(KeywordCollection keywords)
      throws KeywordOptimizerException {
    final TargetingIdeaSelector selector = getSelector(keywords);
    Builder<String, IdeaEstimate> keywordsAndEstimatesBuilder = ImmutableMap.builder();

    int offset = 0;

    try {
      TargetingIdeaPage page;
      do {
        selector.setPaging(new Paging(offset, PAGE_SIZE));
        page = tis.get(selector);

        if (page.getEntries() != null) {
          for (TargetingIdea targetingIdea : page.getEntries()) {
            Map<AttributeType, Attribute> attributeData = Maps.toMap(targetingIdea.getData());

            StringAttribute keywordAttribute =
                (StringAttribute) attributeData.get(AttributeType.KEYWORD_TEXT);
            IdeaEstimate estimate = KeywordOptimizerUtil.toSearchEstimate(attributeData);
            keywordsAndEstimatesBuilder.put(keywordAttribute.getValue(), estimate);
          }
        }
        offset += PAGE_SIZE;
      } while (offset < page.getTotalNumEntries());

    } catch (ApiException e) {
      throw new KeywordOptimizerException("Problem while querying the targeting idea service: "
          + e.getMessage(), e);
    } catch (RemoteException e) {
      throw new KeywordOptimizerException("Problem while connecting to the AdWords API", e);
    }

    return keywordsAndEstimatesBuilder.build();
  }
}
