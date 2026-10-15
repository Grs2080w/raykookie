import { AuthForm } from "@/components/AuthForm";
export const metadata = { title: "Entrar", description: "Acesse o painel de gestão da Raykookie." };
export default function Page() { return <AuthForm mode="login" />; }
