import { t } from '../../../lib/texts'

type ArrayLike = { length: number }

// The messages are in the language of the session's texts (lib/texts), unless given.

export function isRequired (fieldName: string) {
  const msg = () => t('form.required', { field: fieldName })
  return function (value: any) {
    if (typeof value === 'string') {
      return value.length > 0 || msg()
    } else if (Array.isArray(value)) {
      return value.filter(v => v).length > 0 || msg()
    }
    return value !== null && value !== undefined || msg()
  }
}

export function minSelected (min: number, msg?: string) {
  return function (val: ArrayLike) {
    return val.length >= min || msg || t('form.minSelected', { min })
  }
}

export function maxSelected (max: number, msg?: string) {
  return function (val: ArrayLike) {
    return val.length <= max || msg || t('form.maxSelected', { max })
  }
}

export function clampSelected (min: number, max: number, msg?: string) {
  return function (val: ArrayLike) {
    return val.length <= max && val.length >= min || msg || t('form.between', { min, max })
  }
}

export function minText (min: number, msg?: string) {
  return function (val: ArrayLike) {
    return val.length >= min || msg || t('form.minText', { min })
  }
}

export function maxText (max: number, msg?: string) {
  return function (val: ArrayLike) {
    return val.length <= max || msg || t('form.maxText', { max })
  }
}
