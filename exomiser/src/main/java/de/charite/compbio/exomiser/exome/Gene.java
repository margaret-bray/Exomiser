package de.charite.compbio.exomiser.exome;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import jannovar.common.Constants;
import jannovar.common.ModeOfInheritance;
import jannovar.exome.Variant;
import jannovar.genotype.GenotypeCall;
import jannovar.pedigree.Pedigree;

import de.charite.compbio.exomiser.priority.RelevanceScore;
import de.charite.compbio.exomiser.common.FilterType;
import de.charite.compbio.exomiser.filter.FrequencyTriage;
import de.charite.compbio.exomiser.filter.Triage;
import de.charite.compbio.exomiser.filter.PathogenicityTriage;
import de.charite.compbio.exomiser.priority.DynamicPhenoWandererRelevanceScore;
import de.charite.compbio.exomiser.priority.GenewandererRelevanceScore;

/**
 * This class represents a Gene in which {@link jannovar.exome.Variant Variant}
 * objects have been identified by exome sequencing. Note that this class stores
 * information about observed variants and quality scores etc. In contrast, the
 * class {@link jannovar.reference.TranscriptModel TranscriptModel} stores
 * information from UCSC about all genes, irrespective of whether we see a
 * variant in the gene by exome sequencing. Therefore, the program uses
 * information from
 * {@link jannovar.reference.TranscriptModel TranscriptModel} object to annotate
 * variants found by exome sequencing, and stores the results of that annotation
 * in
 * {@link jannovar.exome.Variant Variant} objects. Objects of this class have a
 * list of Variant objects, one for each variant observed in the exome. Additionally,
 * the Gene objects get prioritized for their biomedical relevance to the disease
 * in question, and each such prioritization results in an 
 * {@link exomizer.priority.IRelevanceScore RelevanceScore} object.
 * <P>
 * There are additionally some prioritization procedures that only can be
 * performed on genes (and not on the individual variants). For instance, there
 * are certain genes such as the Mucins or the Olfactory receptor genes that are
 * often found to have variants in WES data but are known not to be the
 * relevant disease genes. Additionally, filtering for autosomal recessive or 
 * dominant patterns in the data is done with this class. This kind of
 * prioritization is done by classes that implement 
 * {@link exomizer.priority.IPriority IPriority}.
 * Recently, the ability to downweight genes with too many variants (now hardcoded to 5)
 * was added).
 * @author Peter Robinson
 * @version 0.21 (16 January, 2013)
 */
public class Gene implements Comparable<Gene> {

    /**
     * A list of all of the variants that affect this gene.
     */
    public List<VariantEvaluation> variant_list = null;
    /**
     * A priority score between 0 (irrelevant) and an arbitrary number (highest
     * prediction for a disease gene) reflecting the predicted relevance of this
     * gene for the disease under study by exome sequencing.
     */
    private float priorityScore = Constants.UNINITIALIZED_FLOAT;
    /**
     * A score representing the combined pathogenicity predictions for the
     * {@link jannovar.exome.Variant Variant} objects associated with this gene.
     */
    private float filterScore = Constants.UNINITIALIZED_FLOAT;

    /**
     * A map of the results of prioritization. The key to the map is from {@link exomizer.common.FilterType FilterType}.
     */
    public Map<FilterType, RelevanceScore> relevanceMap = null;
    /**
     * A Reference to the {@link jannovar.pedigree.Pedigree Pedigree} object for
     * the current VCF file. This object allows us to do segregation analysis
     * for the variants associated with this gene, i.e., to determine if they
     * are compatible with autosomal recessive, autosomal dominant, or X-linked
     * recessive inheritance.
     */
    private static Pedigree pedigree = null;

    /**
     * This method sets the pedigree for all Gene objects (it is a static
     * method). It is intended that pedigree filtering algorithms can use the
     * genotypes associated with this Gene and its Variants as well as the
     * pedigree contained in the static Pedigree object in order to perform
     * inheritance filtering.
     *
     * @param ped The pedigree corresponding to the current VCF file.
     */
    public static void setPedigree(Pedigree ped) {
        Gene.pedigree = ped;
    }

    public void downrankGeneIfMoreVariantsThanThreshold(int threshold) {
        int n = variant_list.size();
        if (threshold <= n) {
            return;
        }
        int diff = threshold - n;
        this.priorityScore = ((float) 1 / diff) * this.priorityScore;
        this.filterScore = ((float) 1 / diff) * this.filterScore;
    }

    /**
     * @return the number of {@link jannovar.exome.Variant Variant} objects for
     * this gene.
     */
    public int getNumberOfVariants() {
        return this.variant_list.size();
    }

    /**
     * Downrank gene because it has a large numbers of variants (under the
     * assumption that such genes are unlikely to be be true disease genes,
     * rather, by chance say 2 of 20 variants are score as highly pathogenic by
     * polyphen, leading to a false positive call. This method downweights the {@link #filterScore}
     * of this gene, which is the aggregate score for the variants.
     *
     * @param threshold Downweighting occurs for variants that have this number
     * or more variants.
     */
    public void downWeightGeneWithManyVariants(int threshold) {
        if (this.variant_list.size() < threshold) {
            return;
        }
        // Start with downweighting factor of 5%
        // For every additional variant, add half again to the factor
        int s = this.variant_list.size();
        float factor = 0.05f;
        float downweight = 0f;
        while (s > threshold) {
            downweight += factor;
            factor *= 1.5;
            s--;
        }
        if (downweight > 1f) {
            downweight = 1f;
        }
        this.filterScore = this.filterScore * (1f - downweight);
        /*
         * filterscore is now at least zero
         */

    }

    /**
     * @return the nth {@link jannovar.exome.Variant Variant} object for this
     * gene.
     */
    public VariantEvaluation getNthVariant(int n) {
        if (n >= this.variant_list.size()) {
            return null;
        } else {
            return this.variant_list.get(n);
        }
    }

    /**
     * Construct the gene by adding the first variant that affects the gene. If
     * the current gene has additional variants, they will be added using the
     * function add_variant.
     *
     * @param var A variant located in this gene.
     */
    public Gene(VariantEvaluation var) {
	variant_list = new ArrayList<VariantEvaluation>();
	variant_list.add(var);
	this.relevanceMap = new HashMap<FilterType,RelevanceScore>();
    }

    /**
     * This function adds additional variants to the current gene. The variants
     * have been identified by parsing the VCF file.
     *
     * @param var A Variant affecting the current gene.
     */
    public void addVariant(VariantEvaluation var) {
        this.variant_list.add(var);
    }

    /**
     * @param rel Result of a prioritization algorithm
     * @param type an integer constant from {@link exomizer.common.FilterType FilterType}
     * representing the filter type
     */
    public void addRelevanceScore(RelevanceScore rel, FilterType type) {
	this.relevanceMap.put(type,rel);
    }

    /**
     * @param type an integer constant from {@link jannovar.common.Constants Constants}
     * representing the filter type
     * @return The IRelevance object corresponding to the filter type.
     */
    public float getRelevanceScore(FilterType type) {
	RelevanceScore ir = this.relevanceMap.get(type);
	if (ir == null) {
	    return 0f; /* This should never happen, but if there is no relevance score, just return 0. */
	}
	return ir.getRelevanceScore();
    }

    public void resetRelevanceScore(FilterType type, float newval) {
	RelevanceScore rel = this.relevanceMap.get(type);
	if (rel == null) {
	    return;/* This should never happen. */
	}
	rel.resetRelevanceScore(newval);
    }

    /**
     * Note that currently, the EntrezGene IDs are associated with the Variants.
     * Probably it would be more natural to associate that with a field of this
     * Gene object. For now, leave it as be, and return an UNINITIALIZED_INT
     * flag if this gene has no {@link jannovar.exome.Variant Variant} objects.
     *
     * @return the NCBI Entrez Gene ID associated with this gene (extracted from
     * one of the Variant objects)
     */
    public int getEntrezGeneID() {
        if (this.variant_list.isEmpty()) {
            return Constants.UNINITIALIZED_INT;
        } else {
            VariantEvaluation ve = this.variant_list.get(0);
            return ve.getVariant().getEntrezGeneID();
        }
    }

    /** 
     * @return the map of {@link exomizer.priority.IRelevanceScore  RelevanceScore} 
     * objects that represent the result of filtering 
     */
    public Map<FilterType,RelevanceScore> getRelevanceMap() { return this.relevanceMap; }
    
    /**
     * Note that currently, the gene symbols are associated with the Variants.
     * Probably it would be more natural to associate that with a field of this
     * Gene object. For now, leave it as be, and return "-" if this gene has no  {@link jannovar.exome.Variant Variant}
     * objects.
     *
     * @return the symbol associated with this gene (extracted from one of the
     * Variant objects)
     */
    public String getGeneSymbol() {
        if (this.variant_list.isEmpty()) {
            return "-";
        } else {
            VariantEvaluation ve = this.variant_list.get(0);
            return ve.getVariant().getGeneSymbol();
        }
    }

    /**
     * Calculates the total priority score for this gene based on data stored in
     * its associated
     * {@link jannovar.exome.Variant Variant} objects. Note that for assumed
     * autosomal recessive variants, the mean of the worst two variants is
     * taken, and for other modes of inheritance,the since worst value is taken.
     * <P> Note that we <b>assume that genes have been filtered for mode of
     * inheritance before this function is called. This means that we do not
     * need to apply separate filtering for mode of inheritance here</b>. The
     * only thing we need to watch out for is whether a variant is homozygous or
     * not (for autosomal recessive inheritance, these variants get counted
     * twice).
     *
     * @param mode Autosomal recessive, doiminant, or X chromosomal recessive.
     */
    public void calculateFilteringScore(ModeOfInheritance mode) {
        this.filterScore = 0f;
        if (variant_list.size() == 0) {
            return;
        }
        List<Float> vals = new ArrayList<Float>();
        if (mode == ModeOfInheritance.AUTOSOMAL_RECESSIVE) {
            for (VariantEvaluation ve : this.variant_list) {
                float x = ve.getFilterScore();
                vals.add(x);
                GenotypeCall gc = ve.getVariant().getGenotype();
                if (Gene.pedigree.containsCompatibleHomozygousVariant(gc)) {
                    vals.add(x); /*
                     * Add the value a second time, it is homozygous
                     */
                }
            }
        } else { /*
             * not autosomal recessive
             */
            for (VariantEvaluation ve : this.variant_list) {
                float x = ve.getFilterScore();
                vals.add(x);
            }
        }
        Collections.sort(vals, Collections.reverseOrder()); /*
         * Sort in descending order
         */
        if (mode == ModeOfInheritance.AUTOSOMAL_RECESSIVE) {
            if (vals.size() < 2) {
                return; /*
                 * Less than two variants, cannot be AR
                 */
            }
            float x = vals.get(0);
            float y = vals.get(1);
            this.filterScore = (x + y) / (2f);
        } else {
            /*
             * Not autosomal recessive, there is just one heterozygous mutation
             * thus return only the single best score.
             */
            this.filterScore = vals.get(0);
        }

    }

    /**
     * Calculate the combined priority score for this gene (the result is stored
     * in the class variable
     * {@link exomizer.exome.Gene#priorityScore}, which is used to help sort the
     * gene.
     */
     public void calculatePriorityScore() {
	 this.priorityScore  = 1f;
	 for (FilterType i : this.relevanceMap.keySet()) {
	    RelevanceScore r = this.relevanceMap.get(i);
	    float x = r.getRelevanceScore();
	    priorityScore *= x;
	 }
     }


    /**
     * @return A list of all variants in the VCF file that affect this gene.
     */
    public List<VariantEvaluation> get_variant_list() {
        return this.variant_list;
    }

    /**
     * @return A list order by descending variant score .
     */
    public List<VariantEvaluation> get_ordered_variant_list() {
        List<Float> vals = new ArrayList<Float>();
        for (VariantEvaluation ve : this.variant_list) {
            Variant v = ve.getVariant();
            float x = ve.getFilterScore();
            vals.add(x);
        }
        Collections.sort(vals, Collections.reverseOrder()); /*
         * Sort in descending order
         */

        List<VariantEvaluation> new_variant_list = new ArrayList<VariantEvaluation>();
        for (float val : vals) {
            for (VariantEvaluation ve : this.variant_list) {
                Variant v = ve.getVariant();
                float x = ve.getFilterScore();
                if (x == val && !new_variant_list.contains(ve)) {
                    new_variant_list.add(ve);// May be bug where have equal scores - vars will get added twice
                }
            }
        }

        return new_variant_list;
    }

    /**
     * @return true if the variants for this gene are consistent with autosomal
     * recessive inheritance, otherwise false.
     */
    public boolean is_consistent_with_recessive() {
        ArrayList<Variant> varList = new ArrayList<Variant>();
        for (VariantEvaluation ve : this.variant_list) {
            Variant v = ve.getVariant();
            varList.add(v);
        }
        return Gene.pedigree.isCompatibleWithAutosomalRecessive(varList);
    }

    /**
     * @return true if the variants for this gene are consistent with autosomal
     * dominant inheritance, otherwise false.
     */
    public boolean is_consistent_with_dominant() {
        ArrayList<Variant> varList = new ArrayList<Variant>();
        for (VariantEvaluation ve : this.variant_list) {
            Variant v = ve.getVariant();
            varList.add(v);
        }
        return Gene.pedigree.isCompatibleWithAutosomalDominant(varList);
    }

    /**
     * @return true if the variants for this gene are consistent with X
     * chromosomal inheritance, otherwise false.
     */
    public boolean is_consistent_with_X() {
        ArrayList<Variant> varList = new ArrayList<Variant>();
        for (VariantEvaluation ve : this.variant_list) {
            Variant v = ve.getVariant();
            varList.add(v);
        }
        return Gene.pedigree.isCompatibleWithXChromosomalRecessive(varList);
    }

    /**
     * @return true if the gene is X chromosomal, otherwise false.
     */
    public boolean is_X_chromosomal() {
        if (this.variant_list.size() < 1) {
            return false;
        }
        VariantEvaluation ve = this.variant_list.get(0);
        Variant v = ve.getVariant();
        return v.is_X_chromosomal();
    }

    public boolean is_Y_chromosomal() {
        if (this.variant_list.size() < 1) {
            return false;
        }
        VariantEvaluation ve = this.variant_list.get(0);
        Variant v = ve.getVariant();
        return v.is_Y_chromosomal();
    }

    /**
     * Calculate the combined score of this gene based on the relevance of the
     * gene (priorityScore) and the predicted effects of the variants
     * (filterScore). <P> Note that this method assumes we have calculate the
     * scores, which is depending on the function {@link #calculateGeneAndVariantScores}
     * having been called.
     *
     * @return a combined score that will be used to rank the gene.
     */
    public float getCombinedScore() {
        //return priorityScore * filterScore;
        return (priorityScore + filterScore) / 2f;
        //return priorityScore;
        //return priorityScore * pathogenicityFilterScore;
    }

//    /**
//     * @return a row for tab-separated value file.
//     */
//    public String getTSVRow() {
//        float humanPhenScore = 0f;
//        float mousePhenScore = 0f;
//        float fishPhenScore = 0f;
//        float rawWalkerScore = 0f;
//        float scaledMaxScore = 0f;
//        float walkerScore = 0f;
//        float exomiser2Score = 0f;
//        float omimScore = 0f;
//        float maxFreq = 0f;
//        float pathogenicityScore = 0f;
//        float polyphen = 0f;
//        float sift = 0f;
//        float mutTaster = 0f;
//        float caddRaw = 0f;
//        String variantType = "";
//        // priority score calculation
//        for (FilterType i : this.relevanceMap.keySet()) {
//            RelevanceScore r = this.relevanceMap.get(i);
//            float x = r.getRelevanceScore();
//            if (i == FilterType.DYNAMIC_PHENOWANDERER_FILTER) {
//                exomiser2Score = x;
//                humanPhenScore = ((DynamicPhenoWandererRelevanceScore) r).getHumanScore();
//                mousePhenScore = ((DynamicPhenoWandererRelevanceScore) r).getMouseScore();
//                fishPhenScore = ((DynamicPhenoWandererRelevanceScore) r).getFishScore();
//                walkerScore = ((DynamicPhenoWandererRelevanceScore) r).getWalkerScore();
//            } else if (i == FilterType.OMIM_FILTER) {
//                omimScore = x;
//            } else if (i == FilterType.GENEWANDERER_FILTER) {
//                walkerScore = x;
//                rawWalkerScore = (float) ((GenewandererRelevanceScore) r).getRawScore();
//                scaledMaxScore = (float) ((GenewandererRelevanceScore) r).getScaledScore();
//            }
//        }
//        for (VariantEvaluation ve : this.variant_list) {
//            float x = ve.getFilterScore();
//            for (FilterType i : ve.getTriageMap().keySet()) {
//                Triage itria = ve.getTriageMap().get(i);
//                if (itria instanceof PathogenicityTriage){
//                    if (((PathogenicityTriage) itria).filterResult() > pathogenicityScore){
//                        variantType = ve.getVariantType();
//                        pathogenicityScore = ((PathogenicityTriage) itria).filterResult();
//                        polyphen = ((PathogenicityTriage) itria).getPolyphen();
//                        sift = ((PathogenicityTriage) itria).getSift();
//                        mutTaster = ((PathogenicityTriage) itria).getMutTaster();
//                        caddRaw = ((PathogenicityTriage) itria).getCADDRaw();
//                        FrequencyTriage ft = (FrequencyTriage) ve.getTriageMap().get(FilterType.FREQUENCY_FILTER);
//                        maxFreq = ft.getMaxFreq();
//                    }
//                }
//            }
//        }
//        
//        String s = String.format("%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s",
//                getGeneSymbol(),
//                getEntrezGeneID(),
//                getPriorityScore(),
//                getFilterScore(),
//                getCombinedScore(),
//                humanPhenScore,
//                mousePhenScore,
//                fishPhenScore,
//                rawWalkerScore,
//                scaledMaxScore,
//                walkerScore,
//                exomiser2Score,
//                omimScore,
//                maxFreq,
//                pathogenicityScore,
//                polyphen,
//                sift,
//                mutTaster,
//                caddRaw,
//                variantType);
//        return s;
//    }

    /**
     * Calculate the priority score of this gene based on the relevance of the
     * gene (priorityScore) <P> Note that this method assumes we have calculate
     * the scores, which is depending on the function {@link #calculateGeneAndVariantScores}
     * having been called.
     *
     * @return a priority score that will be used to rank the gene.
     */
    public float getPriorityScore() {
        return priorityScore;
    }

    /**
     * setter only used for Walker rank based scoring
     */
    public void setPriorityScore(float score) {
        priorityScore = score;
    }

    /**
     * Calculate the filter score of this gene based on the relevance of the
     * gene (filterScore) <P> Note that this method assumes we have calculate
     * the scores, which is depending on the function {@link #calculateGeneAndVariantScores}
     * having been called.
     *
     * @return a filter score that will be used to rank the gene.
     */
    public float getFilterScore() {
        return this.filterScore;
    }

    /**
     * setter only used for Walker rank based scoring
     */
    //public void setFilterScore(float score) {
    //filterScore = score;
    //}
    /**
     * Calculate the gene (priority) and the variant (filtering) scores in
     * preparation for sorting.
     */
    public void calculateGeneAndVariantScores(ModeOfInheritance mode) {
        calculatePriorityScore();
        calculateFilteringScore(mode);
    }

    /**
     * Sort this gene based on priority and filter score. This function
     * satisfies the Interface {@code Comparable}.
     */
    public int compareTo(Gene other) {
        float me = getCombinedScore();
        float you = other.getCombinedScore();
        if (me < you) {
            return 1;
        }
        if (me > you) {
            return -1;
        }
        return 0;
    }

    public Iterator<VariantEvaluation> getVariantEvaluationIterator() {
        Collections.sort(this.variant_list);
        return this.variant_list.iterator();
    }
}
