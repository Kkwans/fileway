import dayjs from "dayjs";
import duration from "dayjs/plugin/duration";
import localizedFormat from "dayjs/plugin/localizedFormat";
import relativeTime from "dayjs/plugin/relativeTime";
import "dayjs/locale/zh-cn";

dayjs.extend(localizedFormat);
dayjs.extend(relativeTime);
dayjs.extend(duration);
dayjs.locale("zh-cn");

export function formatRelativeTime(
  value: string | number | Date,
  baseTime?: string | number | Date
) {
  const target = dayjs(value);
  return baseTime === undefined
    ? target.fromNow()
    : target.from(dayjs(baseTime));
}

export default dayjs;
