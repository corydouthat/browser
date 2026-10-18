/* Languages, Intl locale/time zone and Date coherence. */
export const id = 'intl';

/** UTC offset (minutes, east positive) of `tz` at instant `date`, derived purely from Intl. */
export function intlOffsetMinutes(tz, date) {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const p = {};
  for (const part of dtf.formatToParts(date)) p[part.type] = part.value;
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour % 24, +p.minute, +p.second);
  return Math.round((asUtc - Math.floor(date.getTime() / 1000) * 1000) / 60000);
}

export async function probe() {
  const n = navigator;
  const now = new Date();
  let timeZone = null; let locale = null; let numberLocale = null; let intlOffset = null; let error = null;
  try {
    const ro = new Intl.DateTimeFormat().resolvedOptions();
    timeZone = ro.timeZone;
    locale = ro.locale;
    numberLocale = new Intl.NumberFormat().resolvedOptions().locale;
    intlOffset = intlOffsetMinutes(timeZone, now);
  } catch (e) {
    error = String(e && e.message);
  }
  let languages = null;
  try { languages = Array.from(n.languages); } catch (e) { languages = `error: ${e && e.message}`; }
  const dateString = now.toString();
  const tzName = (dateString.match(/\(([^)]+)\)\s*$/) || [])[1] || null;
  return {
    intl: {
      languages,
      language: n.language,
      timeZone,
      locale,
      numberLocale,
      dateOffsetMinutes: -now.getTimezoneOffset(),
      intlOffsetMinutes: intlOffset,
      dateString,
      dateTzName: tzName,
      toLocaleString: now.toLocaleString(),
      error,
    },
  };
}
