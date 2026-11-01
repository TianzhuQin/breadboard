import { ref, watch } from 'vue'
import { http, saveFile, saveText } from './http'

/**
 * The CSV files the console downloads, as they always were or for Excel. The server's files are
 * UTF-8 without a byte-order mark, which R, Python and the analysis scripts written for earlier
 * versions read as they are. Excel and WPS read such a file in the computer's own encoding instead
 * (on a Chinese Windows, GBK), and Chinese turns into garbage. "For Excel" (Sessions & data) asks
 * the server for the same files with the mark (?excel=true), and puts it before the files the
 * console makes itself. The values are the same either way. The choice is kept in this browser.
 */
const KEY = 'bb-admin-csv-for-excel'

function stored (): boolean {
  try { return localStorage.getItem(KEY) === '1' } catch (e) { return false }
}

export const csvForExcel = ref(stored())
watch(csvForExcel, (on) => {
  try {
    if (on) localStorage.setItem(KEY, '1')
    else localStorage.removeItem(KEY)
  } catch (e) { /* private mode: the choice lasts until the page is reloaded */ }
})

/** The byte-order mark that tells Excel the file is UTF-8. */
export const BOM = '\uFEFF'

/** One of the server's CSV addresses, asking for the Excel file when that is chosen. */
export function csvUrl (path: string, excel = csvForExcel.value): string {
  return excel ? `${path}${path.includes('?') ? '&' : '?'}excel=true` : path
}

/**
 * Download one of the server's CSV files and save it as `fileName`, byte for byte (reading it as text
 * would drop the byte-order mark). Returns its text, to count the rows.
 */
export async function downloadCsv (path: string, fileName: string): Promise<string> {
  const bytes = await http.get<ArrayBuffer>(csvUrl(path), { bytes: true })
  saveFile(fileName, new Blob([bytes], { type: 'text/csv' }))
  return new TextDecoder().decode(bytes)
}

/** Save a CSV the console made itself, with the byte-order mark when Excel is chosen. */
export function saveCsv (fileName: string, text: string) {
  saveText(fileName, csvForExcel.value ? BOM + text : text)
}
