package com.molcon.phibase.main;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.molcon.phibase.excelParse.PhiIdAASeqBean;
import com.molcon.phibase.excelParse.PhibaseCitationDetails;
import com.molcon.phibase.excelParse.PhibaseDisease;
import com.molcon.phibase.excelParse.PhibaseDiseaseIntervention;
import com.molcon.phibase.excelParse.PhibaseDiseaseProcess;
import com.molcon.phibase.excelParse.PhibaseGene;
import com.molcon.phibase.excelParse.PhibaseHost;
import com.molcon.phibase.excelParse.PhibaseInfo;
import com.molcon.phibase.excelParse.PhibasePathogen;
import com.molcon.phibase.excelParse.PhibaseReference;

public class PhibaseExcelParse {
	enum classTypeEnum {
		String, Integer
	};

	public static void main(String[] args) throws Exception {
		
		boolean loadToDB = false;
		if(args.length>0){
			if(args[0].equals("-DBLoad")) {
				loadToDB = true;
				System.out.println("Running parser with -DBLoad option, records will be inserted to DB if there are no errors");
			}				
		}  else {
			System.out.println("Running parser without -DBLoad option, records will not be inserted to DB");
		}

		PhibaseExcelParse phibaseExcelParse = new PhibaseExcelParse();
		
		Properties properties = new Properties();
		Properties dbProperties = new Properties();
		try {
			FileReader reader = new FileReader("resources/phibaseExcelParseConfig.properties");
			properties.load(reader);

			FileReader dbReader = new FileReader(Paths.get("resources/phibaseDBConfig.properties").toAbsolutePath().toFile());
			dbProperties.load(dbReader);

		} catch (IOException e) {
			e.printStackTrace();
		}
		StringBuffer phibaseResultBuffer = new StringBuffer("Phibase validation Report :\n");
		File phibaseResultFile = new File(properties.getProperty("phibaseResultFilePath"));

		String dbUrl = dbProperties.getProperty("dburl");
		String dbUser = dbProperties.getProperty("dbuser");
		String dbPassword = dbProperties.getProperty("dbpassword");

		PhibaseExcelParseDB phibaseExcelParseDB = new PhibaseExcelParseDB(dbUrl, dbUser, dbPassword);
		List<Map<String, Object>> parsingClassesBeanMapList = phibaseExcelParse.parseExcel(properties,
				phibaseResultBuffer, phibaseExcelParseDB, phibaseResultFile);
		
		String totalParsedMsg = "Total no of records parsed:"+parsingClassesBeanMapList.size()+"\n";
		System.out.println(totalParsedMsg);
		phibaseExcelParseDB.writeFile(phibaseResultFile, new Date()+"\n"+totalParsedMsg, false);
		
		String error = checkForDuplicatePhiIdsWithSameAASequence(parsingClassesBeanMapList);
		if ( error.equals("") && loadToDB ) {
			int noOfRecordsInserted = phibaseExcelParseDB.insertExcelParseDataToDB(parsingClassesBeanMapList, phibaseResultFile);
			String noOfRecordsInsertedMsg = "Total No of Records inserted to db :" + noOfRecordsInserted;
			System.out.println(noOfRecordsInsertedMsg);
			phibaseExcelParseDB.writeFile(phibaseResultFile, noOfRecordsInsertedMsg, true);
		} else {
			phibaseExcelParseDB.writeFile(phibaseResultFile, error, true); 
		}

		System.out.println("Parsing completed,For full summary please check the log file at path "+properties.getProperty("phibaseResultFilePath"));
	}

	private static String checkForDuplicatePhiIdsWithSameAASequence(
			List<Map<String, Object>> parsingClassesBeanMapList) {
		
		List<Map<String, Object>> validParsingClassesBeanMaps = new ArrayList<>();
		
		// pass 1 variables
		Map<String, PhiIdAASeqBean> uniprotToSeqMapping = new HashMap<>();
		Set<String> phidsWithSameUniprotButDiffSeq = new HashSet<>();
		
		// pass 2 variables
/*		Map<String, PhiIdAASeqBean> seqToUniprotMapping = new HashMap<>();
		Set<String> phidsWithSameAASeqWithDiffUniprot = new HashSet<>();*/
		
		/// old
		Set<String> allPhiIids = new HashSet<>();
		
		Set<String> uniquePhiIdAASeq = new HashSet<>();
		Set<String> duplicatePhiIdAASeq = new HashSet<>();
		

		StringBuilder builder = new StringBuilder();		
		
		for (Map<String, Object> map : parsingClassesBeanMapList) {
			PhibaseReference phibaseReference = (PhibaseReference) map.get("PhibaseReference");
			PhibaseGene phibaseGene = (PhibaseGene) map.get("PhibaseGene");

			String phiId = phibaseReference.getPhibaseAccessionId();
			String aaSequence = phibaseGene.getAASequence();
			String uniprotId = phibaseGene.getGeneProteinId();
			
			allPhiIids.add(phiId);		
			
			// check aaSeq is nnot null, not blank and not "NA"
			if (null != aaSequence && !aaSequence.trim().equals("") 
					&& !aaSequence.toUpperCase().equals("NA")) {
				
				// pass 1: test for single uniprot containing different aaseq				
				// check if map contains uniprot
				if(uniprotToSeqMapping.containsKey(uniprotId)){
					PhiIdAASeqBean bean = uniprotToSeqMapping.get(uniprotId);
					//if map contains uniprot id then get the bean and check current aaseq 
					// is same as seq present in bean
					if(!aaSequence.equals(bean.getAaSequence())) {
						// if seq dont match then show current phiid and phid present in bean
						phidsWithSameUniprotButDiffSeq.add(phiId+"-->"+bean.getPhiId());
					}
				} 
				// if map does not contain uniprot, then add it map
				else {
					PhiIdAASeqBean phiIdAASeqBean = new PhiIdAASeqBean();
					phiIdAASeqBean.setPhiId(phiId);
					phiIdAASeqBean.setAaSequence(aaSequence);
					uniprotToSeqMapping.put(uniprotId, phiIdAASeqBean);
				}
				
				
				/*// pass 2: test for single aaseq containing different uniprot
				// check if map contains aaseq
				if(seqToUniprotMapping.containsKey(aaSequence)){
					PhiIdAASeqBean bean = seqToUniprotMapping.get(aaSequence);
					//if map contains aaseq id then get the bean and check current uniprot 
					// is same as uniprot present in bean
					if(!uniprotId.equals(bean.getUniprotId())){
						// if uniprot dont match then show current phiid and phid present in bean
						phidsWithSameAASeqWithDiffUniprot.add(phiId+"-->"+bean.getPhiId());
					}					
				}
				// if map does not contain aaseq, then add it map
				else {
					PhiIdAASeqBean phiIdAASeqBean = new PhiIdAASeqBean();
					phiIdAASeqBean.setPhiId(phiId);
					phiIdAASeqBean.setAaSequence(aaSequence);
					phiIdAASeqBean.setUniprotId(uniprotId);
					seqToUniprotMapping.put(aaSequence, phiIdAASeqBean);
				}*/
			}
		}

		if (!phidsWithSameUniprotButDiffSeq.isEmpty()) {
			builder.append("\nSame uniprot Mapped to multiple Seq\n");
			for (String s : phidsWithSameUniprotButDiffSeq) {
				builder.append(s + "\n");
			}
		}
		/*if (!phidsWithSameAASeqWithDiffUniprot.isEmpty()) {
			builder.append("\nSame Seq with multiple uniprot\n");
			for (String s : phidsWithSameAASeqWithDiffUniprot) {
				builder.append(s + "\n");
			}
		}*/
		
		return builder.toString();
	}

	public List<Map<String, Object>> parseExcel(Properties properties, StringBuffer phibaseResultBuffer,
			PhibaseExcelParseDB phibaseExcelParseDB, File phibaseResultFile) throws Exception {

		List<Map<String, Object>> parsingClassesBeanMapList = new ArrayList<Map<String, Object>>();
		
		try {
			File file = new File(properties.getProperty("phibaseInputFilePath"));
			InputStream fileInputStream = new FileInputStream(file);

			// Get the workbook instance for XLS file
			XSSFWorkbook workbook = new XSSFWorkbook(fileInputStream);

			// Get first sheet from the workbook
			Sheet sheet = workbook.getSheetAt(0);

			// Get iterator of each row and read the column as per index which
			// are specified in properties file

			// get header properties key list
			List<String> headerKeyList = new ArrayList<String>();
			Iterator<Cell> cellHeaderIterator = sheet.getRow(1).cellIterator();
			while (cellHeaderIterator.hasNext()) {
				Cell cell = cellHeaderIterator.next();
				headerKeyList.add(getPropertyKey(cell.toString()));

			}

			// read the value by iterating rows
			Iterator<Row> rowIterator = sheet.iterator();
			while (rowIterator.hasNext()) {
				Row row = rowIterator.next();
				if (row.getRowNum() == 0 || row.getRowNum() == 1)
					continue;
				Cell cell = row.getCell(3);

				if (cell == null || cell.getCellType() == XSSFCell.CELL_TYPE_BLANK)
					continue;
				// get object of all parsing classes
				Map<String, Object> parsingClassesBeanMap = getParsingClassesBeanMap();

				// get the properties value w.r.t headerkey
				for (String headerKey : headerKeyList) {
					String propertyKeyValue = properties.getProperty(headerKey);
					String[] propertyValue = null;
					if (propertyKeyValue != null) {
						propertyValue = propertyKeyValue.split(":");
						int excelIndex = Integer.parseInt(propertyValue[0]);
						String className = propertyValue[1];
						String propertiesName = propertyValue[2];

						// now get the value w.r.t. index from excel of each row
						// and set in bean object
						Cell excelIndexcell = row.getCell(excelIndex);
						String excelIndexcellValue = "";
						if (excelIndexcell != null) {
							excelIndexcellValue = getCellValueType(excelIndexcell);
						}

						if (headerKey.equals("Multiplemutation")) {
							if (!excelIndexcellValue.equals("") && !excelIndexcellValue.equals("no")
									&& !excelIndexcellValue.equals("na")) {
								if (!checkMultipleMutationFormat(excelIndexcellValue)) {
									phibaseResultBuffer.append("\nError in format of Data in line no:"
											+ (row.getRowNum() + 1) + " of " + headerKey + " :" + excelIndexcellValue);
								}
							}
						}

						// get the Parsing Bean object w.r.t class name
						Object classNameObject = parsingClassesBeanMap.get(className);

						// use the reflection and dynamically initialize the
						// properties of classes i.e invoking setter method
						Class[] parameterTypes = null;
						Object[] arguments = null;
						String methodName = "";
						Method setMethod = null;

						parameterTypes = new Class[] { String.class };
						methodName = "set" + Character.toUpperCase(propertiesName.charAt(0))
								+ propertiesName.substring(1);
						setMethod = classNameObject.getClass().getMethod(methodName, parameterTypes);

						arguments = new Object[] { excelIndexcellValue };

						setMethod.invoke(classNameObject, arguments);
						parsingClassesBeanMap.put(className, classNameObject);
					}
				}
				// put in a list of parsing classes bean
				parsingClassesBeanMapList.add(parsingClassesBeanMap);
			}
			phibaseExcelParseDB.writeFile(phibaseResultFile, phibaseResultBuffer.toString(), false);
		}  catch (Exception e) {
			System.out.println("Parsing unsuccessful");
			e.printStackTrace();
		}
		return parsingClassesBeanMapList;
	}

	public Map<String, Object> getParsingClassesBeanMap() {
		Map<String, Object> parsingClassesBeanMap = new LinkedHashMap<String, Object>();

		parsingClassesBeanMap.put("PhibaseReference", (Object) new PhibaseReference());
		parsingClassesBeanMap.put("PhibaseGene", (Object) new PhibaseGene());
		parsingClassesBeanMap.put("PhibasePathogen", (Object) new PhibasePathogen());
		parsingClassesBeanMap.put("PhibaseHost", (Object) new PhibaseHost());
		parsingClassesBeanMap.put("PhibaseDisease", (Object) new PhibaseDisease());
		parsingClassesBeanMap.put("PhibaseDiseaseProcess", (Object) new PhibaseDiseaseProcess());
		parsingClassesBeanMap.put("PhibaseDiseaseIntervention", (Object) new PhibaseDiseaseIntervention());
		parsingClassesBeanMap.put("PhibaseCitationDetails", (Object) new PhibaseCitationDetails());
		parsingClassesBeanMap.put("PhibaseInfo", (Object) new PhibaseInfo());

		return parsingClassesBeanMap;
	}

	public String getPropertyKey(String key) {
		return key.replaceAll("[^A-Za-z0-9]", "");
	}

	public static String getCellValueType(Cell cell) {
		String cellValue = "";
		switch (cell.getCellType()) {
		case Cell.CELL_TYPE_NUMERIC:
			cellValue = String.valueOf((int) cell.getNumericCellValue());
			break;
		case Cell.CELL_TYPE_STRING:
			cellValue = cell.getStringCellValue().trim();
			break;
		default:
			break;
		}

		return cellValue;
	}

	public boolean checkMultipleMutationFormat(String multipleMutation) {
		boolean multipleMutationFlag = false;
		if (multipleMutation.equals("no") || multipleMutation.equals("")) {
			multipleMutationFlag = true;
		} else {
			Pattern pattern = Pattern.compile("^(PHI:[0-9]+;?\\s*)+$");
			Matcher matcher = pattern.matcher(multipleMutation);
			if (matcher.matches()) {
				multipleMutationFlag = true;
			} else {
				multipleMutationFlag = false;
			}
		}
		return multipleMutationFlag;
	}

}
