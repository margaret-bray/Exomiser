/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2018 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.core.analysis.util;

import com.google.common.collect.ImmutableList;
import de.charite.compbio.jannovar.mendel.*;
import de.charite.compbio.jannovar.pedigree.Pedigree;
import htsjdk.variant.variantcontext.VariantContext;
import org.monarchinitiative.exomiser.core.genome.VariantContextSampleGenotypeConverter;
import org.monarchinitiative.exomiser.core.model.AlleleCall;
import org.monarchinitiative.exomiser.core.model.SampleGenotype;
import org.monarchinitiative.exomiser.core.model.VariantEvaluation;
import org.monarchinitiative.exomiser.core.model.frequency.FrequencyData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Native wrapper for the Jannovar {@link MendelianInheritanceChecker} rather than relying on the Jannovar HTSJDK bridge.
 *
 * @author Jules Jacobsen <j.jacobsen@qmul.ac.uk>
 * @since 10.0.0
 */
public class InheritanceModeAnnotator {

    private static final Logger logger = LoggerFactory.getLogger(InheritanceModeAnnotator.class);

    private final MendelianInheritanceChecker mendelChecker;
    private final InheritanceModeMaxMafs moiMaxMafs;

    public InheritanceModeAnnotator(Pedigree pedigree, InheritanceModeMaxMafs inheritanceModeMaxMafs) {
        this.mendelChecker = new MendelianInheritanceChecker(pedigree);
        this.moiMaxMafs = inheritanceModeMaxMafs;
    }

    /**
     * This method will check the supplied list of {@link VariantEvaluation} against the pedigree supplied in the class
     * constructor to determine the modes of inheritance with which the variants are compatible. It does not alter the
     * input objects. It is expected that the input variants have been pre-filtered, failure to do so will result in
     * overly-long computation time and potentially incorrect analyses.
     *
     * @param variantEvaluations a pre-filtered list of variants
     * @return a map of inheritance modes and the variants which are compatible with them
     */
    public Map<ModeOfInheritance, List<VariantEvaluation>> computeCompatibleInheritanceModes(List<VariantEvaluation> variantEvaluations) {
        List<GenotypeCalls> genotypeCalls = buildGenotypeCalls(variantEvaluations);
        try {
            Map<ModeOfInheritance, ImmutableList<GenotypeCalls>> compatibilityCalls = mendelChecker.checkMendelianInheritance(genotypeCalls);
            logger.debug("{}", compatibilityCalls);
            return variantsGroupedByCompatibleMode(compatibilityCalls);
        } catch (IncompatiblePedigreeException e) {
            logger.error("Problem with annotating VariantContext for Mendelian inheritance.", e);
        }
        return Collections.emptyMap();
    }

    /**
     * This method will check the supplied list of {@link VariantEvaluation} against the pedigree supplied in the class
     * constructor to determine the sub-modes of inheritance with which the variants are compatible. It does not alter the
     * input objects. It is expected that the input variants have been pre-filtered, failure to do so will result in
     * overly-long computation time and potentially incorrect analyses.
     *
     * @param variantEvaluations a pre-filtered list of variants
     * @return a map of sub-inheritance modes and the variants which are compatible with them
     */
    public Map<SubModeOfInheritance, List<VariantEvaluation>> computeCompatibleInheritanceSubModes(List<VariantEvaluation> variantEvaluations) {
        List<GenotypeCalls> genotypeCalls = buildGenotypeCalls(variantEvaluations);
        try {
            Map<SubModeOfInheritance, ImmutableList<GenotypeCalls>> compatibilityCalls = mendelChecker.checkMendelianInheritanceSub(genotypeCalls);
            logger.debug("{}", compatibilityCalls);
            return variantsGroupedByCompatibleSubMode(compatibilityCalls);
        } catch (IncompatiblePedigreeException e) {
            logger.error("Problem with annotating VariantContext for Mendelian inheritance.", e);
        }
        return Collections.emptyMap();
    }


    private Map<ModeOfInheritance, List<VariantEvaluation>> variantsGroupedByCompatibleMode(Map<ModeOfInheritance, ImmutableList<GenotypeCalls>> compatibilityCalls) {
        Map<ModeOfInheritance, List<VariantEvaluation>> results = new EnumMap<>(ModeOfInheritance.class);
        for (Map.Entry<ModeOfInheritance, ImmutableList<GenotypeCalls>> entry : compatibilityCalls.entrySet()) {
            ModeOfInheritance compatibleMode = entry.getKey();
            if (compatibleMode != ModeOfInheritance.ANY) {
                List<GenotypeCalls> genotypeCalls = entry.getValue();
                //inheritance maximum minor allele frequency
                float maxFreqForMode = moiMaxMafs.getMaxFreqForMode(compatibleMode);
                List<VariantEvaluation> compatibleVariants = getCompatibleVariantsUnderFrequencyThreshold(genotypeCalls, maxFreqForMode);
                if (!compatibleVariants.isEmpty()) {
                    results.put(compatibleMode, compatibleVariants);
                }
            }
        }
        return results;
    }

    private Map<SubModeOfInheritance, List<VariantEvaluation>> variantsGroupedByCompatibleSubMode(Map<SubModeOfInheritance, ImmutableList<GenotypeCalls>> compatibilityCalls) {
        Map<SubModeOfInheritance, List<VariantEvaluation>> results = new EnumMap<>(SubModeOfInheritance.class);
        for (Map.Entry<SubModeOfInheritance, ImmutableList<GenotypeCalls>> entry : compatibilityCalls.entrySet()) {
            SubModeOfInheritance compatibleSubMode = entry.getKey();
            if (compatibleSubMode != SubModeOfInheritance.ANY) {
                List<GenotypeCalls> genotypeCalls = entry.getValue();
                //Add filter for mode of inheritance frequency here
                float maxFreqForMode = moiMaxMafs.getMaxFreqForSubMode(compatibleSubMode);
                List<VariantEvaluation> compatibleVariants = getCompatibleVariantsUnderFrequencyThreshold(genotypeCalls, maxFreqForMode);
                if (!compatibleVariants.isEmpty()) {
                    results.put(compatibleSubMode, compatibleVariants);
                }
            }
        }
        return results;
    }

    private List<VariantEvaluation> getCompatibleVariantsUnderFrequencyThreshold(List<GenotypeCalls> genotypeCalls, float maxFreqForMode) {
        List<VariantEvaluation> compatibleVariants = new ArrayList<>();
        for (GenotypeCalls callResults : genotypeCalls) {
            VariantEvaluation variantEvaluation = (VariantEvaluation) callResults.getPayload();
            FrequencyData frequencyData = variantEvaluation.getFrequencyData();
            if (frequencyData.getMaxFreq() <= maxFreqForMode) {
                compatibleVariants.add(variantEvaluation);
            }
        }
        return compatibleVariants;
    }

    private List<GenotypeCalls> buildGenotypeCalls(List<VariantEvaluation> variantEvaluations) {
        ArrayList<GenotypeCalls> result = new ArrayList<>();

        for (VariantEvaluation variantEvaluation : variantEvaluations) {
            GenotypeCallsBuilder builder = new GenotypeCallsBuilder();
            builder.setPayload(variantEvaluation);

            ChromosomeType chromosomeType = toChromosomeType(variantEvaluation.getChromosome());
            builder.setChromType(chromosomeType);

            //This could be moved into the VariantFactory and a getSampleGenotypes() method added to the VariantEvaluation
            //then we can mostly discard the VariantContext, apart from writing out again...
            int altAlleleId = variantEvaluation.getAltAlleleId();
            VariantContext variantContext = variantEvaluation.getVariantContext();
            Map<String, SampleGenotype> sampleGenotypes = VariantContextSampleGenotypeConverter.createAlleleSampleGenotypes(variantContext, altAlleleId);
            logger.debug("Converting {} {} {}", variantContext.getReference(), variantContext.getAlternateAllele(altAlleleId), variantContext
                    .getGenotypes());
            for (Map.Entry<String, SampleGenotype> entry : sampleGenotypes.entrySet()) {
                String sampleName = entry.getKey();
                SampleGenotype sampleGenotype = entry.getValue();

                GenotypeBuilder gtBuilder = new GenotypeBuilder();
                for (AlleleCall alleleCall : sampleGenotype.getCalls()) {
                    switch (alleleCall) {
                        case REF:
                            gtBuilder.getAlleleNumbers().add(Genotype.REF_CALL);
                            break;
                        case ALT:
                            gtBuilder.getAlleleNumbers().add(1);
                            break;
                        case OTHER_ALT:
                            gtBuilder.getAlleleNumbers().add(2);
                            break;
                        case NO_CALL:
                        default:
                            gtBuilder.getAlleleNumbers().add(Genotype.NO_CALL);
                    }
                }
                Genotype genotype = gtBuilder.build();
                logger.debug("Converted {} {} to {}", sampleName, sampleGenotype, genotype);
                builder.getSampleToGenotype().put(sampleName, genotype);
            }
            result.add(builder.build());
        }

        return result;
    }

    private ChromosomeType toChromosomeType(int chromosome) {
        switch (chromosome) {
            case 23:
                return ChromosomeType.X_CHROMOSOMAL;
            case 24:
                return ChromosomeType.Y_CHROMOSOMAL;
            case 25:
                return ChromosomeType.MITOCHONDRIAL;
            default:
                return ChromosomeType.AUTOSOMAL;
        }
    }

}