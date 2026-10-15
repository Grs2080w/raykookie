import { AuthForm } from "@/components/AuthForm";
export const metadata = { title: "Criar conta", description: "Crie sua conta para gerir a confeitaria no painel da Raykookie." };
export default function Page() { return <AuthForm mode="register" />; }
