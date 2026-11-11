import { useEffect, useState } from 'react';

/** L'ora di adesso, aggiornata ogni tanto: per i conti alla rovescia (il tempo per pagare). */
export function useAdesso(ogniMs = 15_000): Date {
  const [adesso, setAdesso] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setAdesso(new Date()), ogniMs);
    return () => clearInterval(timer);
  }, [ogniMs]);
  return adesso;
}
