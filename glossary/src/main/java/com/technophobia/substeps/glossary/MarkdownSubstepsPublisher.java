/*
 *	Copyright Technophobia Ltd 2012
 *
 *   This file is part of Substeps.
 *
 *    Substeps is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Substeps is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with Substeps.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.technophobia.substeps.glossary;

import org.apache.commons.lang.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;

/**
 * @author ian
 * 
 */
public class MarkdownSubstepsPublisher extends FileBasedGlossaryPublisher  implements GlossaryPublisher {

    @Override
    public String getDefaultFileName(){
        return "stepimplementations.md";
    }


    /**
     * @param sectionSorted
     */
    @Override
    public String buildFileContents(final Map<String, Collection<StepDescriptor>> sectionSorted) {
        final StringBuilder buf = new StringBuilder();

        final Set<Entry<String, Collection<StepDescriptor>>> entrySet = sectionSorted.entrySet();

        for (final Entry<String, Collection<StepDescriptor>> e : entrySet) {
            buf.append(String.format(TABLE_ROW_SECTION_FORMAT, e.getKey())).append("\n");

            buildStepTagRows(buf, e.getValue());
        }

        buf.append("</table></body></html>");
        return buf.toString();
    }


    private void buildStepTagRows(final StringBuilder buf, final Collection<StepDescriptor> infos) {

//        Collections.sort(infos, new Comparator<StepDescriptor>() {
//            public int compare(final StepDescriptor s1, final StepDescriptor s2) {
//                return s1.getExpression().compareTo(s2.getExpression());
//            }
//        });

        for (final StepDescriptor info : infos) {

            System.out.println("info non escaped: " + info.getExpression() + "\n\tescaped:\n"
                    + StringEscapeUtils.escapeHtml(info.getExpression()));


            buf.append(
                    String.format(TABLE_ROW_FORMAT,
                            StringEscapeUtils.escapeHtml(info.getExpression()), info.getExample().replaceAll("\n", " "),
                            StringEscapeUtils.escapeHtml(info.getDescription()).replaceAll("\n", " "))).append("\n");

        }
    }

    private static final String TABLE_ROW_SECTION_FORMAT = "%s\n" +
            "==========\n" +
            "| **Keyword**  | **Example**  | **Description** |\n" +
            "| :------------ |:---------------| :-----|";

    private static final String TABLE_ROW_FORMAT = "| %s | %s | %s |";

}
